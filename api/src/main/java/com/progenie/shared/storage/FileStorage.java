package com.progenie.shared.storage;

import java.io.InputStream;
import java.time.Instant;

/**
 * Private object storage for uploaded and generated files: KYC documents, ticket photos, receipts, data exports.
 * Adapters: {@link LocalFileStorage} (disk, the default) and {@link S3FileStorage} (any S3-compatible bucket),
 * chosen with {@code progenie.storage.type}. Callers never know which one is active.
 *
 * <p>Uploads go through the API on purpose: the bytes are checked (size, magic bytes) before anything is stored.
 * Downloads don't: {@link #link} gives the browser a short-lived URL to fetch the file directly.
 */
public interface FileStorage {

    /** Stores the content under {@code key} (e.g. "kyc/{genieId}/{docId}.pdf"). */
    void put(String key, InputStream content, long size, String contentType);

    /** Opens a stored object for reading. Throws 404 FILE_NOT_FOUND when it doesn't exist. */
    InputStream get(String key);

    boolean exists(String key);

    void delete(String key);

    /**
     * A URL the browser can open without logging in, valid for {@code progenie.storage.link-ttl}: a presigned URL
     * on S3, a signed {@code /api/v1/files/...} path locally. Callers check access before asking for one.
     *
     * @param inline true to show the file in the browser (images, PDFs in a new tab), false to download it
     */
    FileLink link(String key, String fileName, String contentType, boolean inline);

    /** A download link and when it stops working. */
    record FileLink(String url, Instant expiresAt) {
    }
}
