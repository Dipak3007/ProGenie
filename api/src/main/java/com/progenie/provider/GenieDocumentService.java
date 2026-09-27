package com.progenie.provider;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.progenie.provider.GenieProfileDtos.DocumentDto;
import com.progenie.shared.config.AppProperties;
import com.progenie.shared.error.ApiException;
import com.progenie.shared.storage.FileStorage;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

/**
 * KYC documents (Aadhaar, PAN, selfie, certificates). Only a masked number is stored, never the
 * full ID number. Files go to private object storage; the file type is checked by its magic bytes,
 * not by the name or the browser-supplied content type.
 */
@Service
public class GenieDocumentService {

    static final Set<String> DOC_TYPES = Set.of("AADHAAR", "PAN", "SELFIE", "CERTIFICATE", "OTHER");
    private static final Map<String, String> EXTENSIONS = Map.of(
        "application/pdf", "pdf", "image/jpeg", "jpg", "image/png", "png", "image/webp", "webp");

    /** What an admin or the owner needs to stream a document back. */
    public record StoredDocument(String objectKey, String contentType, String fileName) {
    }

    private final JdbcClient jdbc;
    private final FileStorage storage;
    private final long maxBytes;

    public GenieDocumentService(JdbcClient jdbc, FileStorage storage, AppProperties props) {
        this.jdbc = jdbc;
        this.storage = storage;
        this.maxBytes = props.storage().maxUploadBytes();
    }

    @Transactional(readOnly = true)
    public List<DocumentDto> list(UUID genieId) {
        return jdbc.sql("""
                SELECT id, doc_type, masked_number, status, file_name, content_type, size_bytes, review_note, created_at
                  FROM genie_documents WHERE genie_id = :id ORDER BY created_at DESC
                """)
            .param("id", genieId)
            .query(DocumentDto.class)
            .list();
    }

    @Transactional
    public DocumentDto upload(UUID genieId, String docType, String numberLast4, MultipartFile file) {
        String type = docType == null ? "" : docType.trim().toUpperCase();
        if (!DOC_TYPES.contains(type)) {
            throw ApiException.badRequest("INVALID_DOC_TYPE", "Document type must be one of " + DOC_TYPES);
        }
        requireEditable(genieId);
        if (file == null || file.isEmpty()) {
            throw ApiException.badRequest("FILE_REQUIRED", "Please attach a file");
        }
        if (file.getSize() > maxBytes) {
            throw ApiException.badRequest("FILE_TOO_LARGE", "Files can be at most " + (maxBytes / (1024 * 1024)) + " MB");
        }
        byte[] bytes = readAll(file);
        String contentType = sniffContentType(bytes);
        if (contentType == null) {
            throw ApiException.badRequest("UNSUPPORTED_FILE", "Upload a PDF, JPG, PNG or WEBP file");
        }
        if ("SELFIE".equals(type) && "application/pdf".equals(contentType)) {
            throw ApiException.badRequest("UNSUPPORTED_FILE", "A selfie must be a photo (JPG, PNG or WEBP)");
        }
        String masked = maskNumber(type, numberLast4);

        UUID docId = UUID.randomUUID();
        String key = "kyc/" + genieId + "/" + docId + "." + EXTENSIONS.get(contentType);
        storage.put(key, new ByteArrayInputStream(bytes), bytes.length, contentType);
        return jdbc.sql("""
                INSERT INTO genie_documents (id, genie_id, doc_type, object_key, masked_number, status,
                                             file_name, content_type, size_bytes)
                VALUES (:id, :g, :type, :key, :masked, 'PENDING', :name, :ct, :size)
                RETURNING id, doc_type, masked_number, status, file_name, content_type, size_bytes, review_note, created_at
                """)
            .param("id", docId)
            .param("g", genieId)
            .param("type", type)
            .param("key", key)
            .param("masked", masked)
            .param("name", safeFileName(file.getOriginalFilename()))
            .param("ct", contentType)
            .param("size", bytes.length)
            .query(DocumentDto.class)
            .single();
    }

    @Transactional
    public void delete(UUID genieId, UUID docId) {
        requireEditable(genieId);
        record Doc(String objectKey, String status) {
        }
        Doc doc = jdbc.sql("SELECT object_key, status FROM genie_documents WHERE id = :id AND genie_id = :g")
            .param("id", docId).param("g", genieId)
            .query(Doc.class).optional()
            .orElseThrow(() -> ApiException.notFound("DOCUMENT_NOT_FOUND", "Document not found"));
        if ("APPROVED".equals(doc.status())) {
            throw ApiException.unprocessable("DOCUMENT_LOCKED", "An approved document cannot be deleted");
        }
        jdbc.sql("DELETE FROM genie_documents WHERE id = :id").param("id", docId).update();
        storage.delete(doc.objectKey());
    }

    /** Owner (genieId given) or admin (genieId null) opens a document. */
    @Transactional(readOnly = true)
    public StoredDocument find(UUID docId, UUID ownerOrNull) {
        return jdbc.sql("""
                SELECT object_key, coalesce(content_type, 'application/octet-stream') AS content_type,
                       coalesce(file_name, doc_type || '-' || id) AS file_name
                  FROM genie_documents
                 WHERE id = :id AND (CAST(:owner AS uuid) IS NULL OR genie_id = :owner)
                """)
            .param("id", docId)
            .param("owner", ownerOrNull)
            .query(StoredDocument.class)
            .optional()
            .orElseThrow(() -> ApiException.notFound("DOCUMENT_NOT_FOUND", "Document not found"));
    }

    public InputStream open(StoredDocument doc) {
        return storage.get(doc.objectKey());
    }

    /** Documents cannot change while an admin is reviewing them. */
    private void requireEditable(UUID genieId) {
        String status = jdbc.sql("SELECT verification_status FROM genie_profiles WHERE user_id = :id")
            .param("id", genieId).query(String.class).optional()
            .orElseThrow(() -> ApiException.notFound("GENIE_PROFILE_NOT_FOUND", "Genie profile not found"));
        if ("UNDER_REVIEW".equals(status)) {
            throw ApiException.unprocessable("UNDER_REVIEW", "Your documents are being reviewed; please wait for the result");
        }
        if ("REJECTED".equals(status) || "SUSPENDED".equals(status)) {
            throw ApiException.unprocessable("INVALID_STATE", "Your profile is " + status);
        }
    }

    static String maskNumber(String docType, String last4) {
        boolean needsNumber = "AADHAAR".equals(docType) || "PAN".equals(docType);
        if (!needsNumber) {
            return null;
        }
        if (last4 == null || !last4.trim().matches("^[A-Za-z0-9]{4}$")) {
            throw ApiException.badRequest("LAST4_REQUIRED", "Enter the last 4 characters of your " + docType + " number");
        }
        String tail = last4.trim().toUpperCase();
        return "AADHAAR".equals(docType) ? "XXXX-XXXX-" + tail : "XXXXXX" + tail;
    }

    /** Detects PDF, JPEG, PNG and WEBP from the first bytes; anything else is rejected. */
    static String sniffContentType(byte[] b) {
        if (b.length >= 4 && b[0] == '%' && b[1] == 'P' && b[2] == 'D' && b[3] == 'F') {
            return "application/pdf";
        }
        if (b.length >= 3 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[2] & 0xFF) == 0xFF) {
            return "image/jpeg";
        }
        if (b.length >= 8 && (b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G') {
            return "image/png";
        }
        if (b.length >= 12 && b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F'
            && b[8] == 'W' && b[9] == 'E' && b[10] == 'B' && b[11] == 'P') {
            return "image/webp";
        }
        return null;
    }

    private static String safeFileName(String original) {
        if (!StringUtils.hasText(original)) {
            return null;
        }
        String name = StringUtils.getFilename(original.replace('\\', '/'));
        name = name == null ? null : name.replaceAll("[^A-Za-z0-9._ -]", "_");
        return name == null || name.length() <= 200 ? name : name.substring(name.length() - 200);
    }

    private static byte[] readAll(MultipartFile file) {
        try {
            return file.getBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
