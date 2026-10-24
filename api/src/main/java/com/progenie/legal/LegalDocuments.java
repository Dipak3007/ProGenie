package com.progenie.legal;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.progenie.shared.config.CompanyProperties;
import com.progenie.shared.config.QueryConstants;
import com.progenie.shared.error.ApiException;
import com.progenie.shared.util.QueryUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Policy documents. An admin edits one draft per kind and publishes it as the next version; published
 * versions are never changed and stay readable. Bodies are Markdown (the web app sanitises the HTML).
 * Company placeholders like {@code [Company legal name]} are filled from {@code progenie.company.*} when set.
 */
@Service
public class LegalDocuments {

    private static final Logger log = LoggerFactory.getLogger(LegalDocuments.class);

    public record DocumentDto(UUID id, LegalKind kind, int version, String title, String body, String changeSummary,
                              boolean requiresReacceptance, LocalDate effectiveFrom, OffsetDateTime publishedAt,
                              OffsetDateTime updatedAt) {
    }

    public record VersionDto(int version, String title, String changeSummary, boolean requiresReacceptance,
                             LocalDate effectiveFrom, OffsetDateTime publishedAt) {
    }

    public record DraftRequest(String title, String body, String changeSummary, Boolean requiresReacceptance,
                               LocalDate effectiveFrom) {
    }

    /** Public contact details for the Privacy page and Contact Us. Blank values mean "not set yet". */
    public record CompanyDto(String legalName, String address, String city, String supportEmail, String supportPhone,
                             String grievanceName, String grievanceEmail) {
    }

    private final JdbcClient jdbc;
    private final CompanyProperties company;

    public LegalDocuments(JdbcClient jdbc, CompanyProperties company) {
        this.jdbc = jdbc;
        this.company = company;
    }

    /** The latest published version. */
    @Transactional(readOnly = true)
    public DocumentDto current(LegalKind kind) {
        return jdbc.sql(QueryUtil.get(QueryConstants.GET_LEGAL_CURRENT))
            .param("k", kind.name()).query(DocumentDto.class).optional()
            .map(this::withCompany)
            .orElseThrow(() -> ApiException.notFound("LEGAL_DOCUMENT_NOT_FOUND", "This document isn't published yet"));
    }

    @Transactional(readOnly = true)
    public DocumentDto version(LegalKind kind, int version) {
        return jdbc.sql(QueryUtil.get(QueryConstants.GET_LEGAL_VERSION))
            .param("k", kind.name()).param("v", version).query(DocumentDto.class).optional()
            .map(this::withCompany)
            .orElseThrow(() -> ApiException.notFound("LEGAL_DOCUMENT_NOT_FOUND", "No such version"));
    }

    @Transactional(readOnly = true)
    public List<VersionDto> versions(LegalKind kind) {
        return jdbc.sql(QueryUtil.get(QueryConstants.GET_LEGAL_VERSIONS))
            .param("k", kind.name()).query(VersionDto.class).list();
    }

    public CompanyDto company() {
        return new CompanyDto(company.legalName(), company.address(), company.city(), company.supportEmail(),
            company.supportPhone(), company.grievanceName(), company.grievanceEmail());
    }

    // ------------------------------------------------------------------ admin

    /** Every version and draft, newest first per kind, with the raw text (placeholders not filled). */
    @Transactional(readOnly = true)
    public List<DocumentDto> adminList() {
        return jdbc.sql(QueryUtil.get(QueryConstants.GET_LEGAL_ADMIN_LIST)).query(DocumentDto.class).list();
    }

    /** Creates or updates the one draft of this kind (the next version number). */
    @Transactional
    public DocumentDto saveDraft(LegalKind kind, DraftRequest req, UUID adminId) {
        if (req.title() == null || req.title().isBlank() || req.body() == null || req.body().isBlank()) {
            throw ApiException.badRequest("DRAFT_INCOMPLETE", "A title and text are required");
        }
        if (req.title().length() > 150 || (req.changeSummary() != null && req.changeSummary().length() > 300)) {
            throw ApiException.badRequest("DRAFT_TOO_LONG", "The title or change summary is too long");
        }
        jdbc.sql(QueryUtil.get(QueryConstants.LOCK_LEGAL_DRAFT)).param("k", "legal-draft:" + kind.name()).query().singleRow();
        int updated = jdbc.sql(QueryUtil.get(QueryConstants.UPDATE_LEGAL_DRAFT))
            .param("title", req.title().trim()).param("body", req.body()).param("summary", req.changeSummary())
            .param("re", Boolean.TRUE.equals(req.requiresReacceptance())).param("eff", req.effectiveFrom()).param("k", kind.name())
            .update();
        if (updated == 0) {
            jdbc.sql(QueryUtil.get(QueryConstants.INSERT_LEGAL_DRAFT))
                .param("k", kind.name()).param("title", req.title().trim()).param("body", req.body())
                .param("summary", req.changeSummary()).param("re", Boolean.TRUE.equals(req.requiresReacceptance()))
                .param("eff", req.effectiveFrom()).param("admin", adminId)
                .update();
        }
        return jdbc.sql(QueryUtil.get(QueryConstants.GET_LEGAL_DRAFT)).param("k", kind.name())
            .query(DocumentDto.class).single();
    }

    @Transactional
    public void deleteDraft(LegalKind kind) {
        jdbc.sql(QueryUtil.get(QueryConstants.DELETE_LEGAL_DRAFT)).param("k", kind.name()).update();
    }

    /**
     * Publishes a draft. If it requires re-acceptance, every affected user sees it after their next login and
     * can't book (or accept jobs) until they accept it.
     */
    @Transactional
    public DocumentDto publish(UUID documentId) {
        DocumentDto d = jdbc.sql(QueryUtil.get(QueryConstants.LOCK_LEGAL_DOCUMENT)).param("id", documentId)
            .query(DocumentDto.class).optional()
            .orElseThrow(() -> ApiException.notFound("LEGAL_DOCUMENT_NOT_FOUND", "Document not found"));
        if (d.publishedAt() != null) {
            throw ApiException.unprocessable("ALREADY_PUBLISHED", "This version is already published");
        }
        jdbc.sql(QueryUtil.get(QueryConstants.UPDATE_LEGAL_PUBLISH))
            .param("id", documentId).update();
        log.info("Published {} v{} (re-acceptance: {})", d.kind(), d.version(), d.requiresReacceptance());
        return jdbc.sql(QueryUtil.get(QueryConstants.GET_LEGAL_DOCUMENT)).param("id", documentId).query(DocumentDto.class).single();
    }

    private DocumentDto withCompany(DocumentDto d) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("[Company legal name]", company.legalName());
        values.put("[Registered address]", company.address());
        values.put("[City]", company.city());
        values.put("[support email]", company.supportEmail());
        values.put("[partner support email]", company.supportEmail());
        values.put("[Grievance officer name]", company.grievanceName());
        values.put("[grievance email]", company.grievanceEmail());
        String body = d.body();
        for (var e : values.entrySet()) {
            if (e.getValue() != null && !e.getValue().isBlank()) {
                body = body.replace(e.getKey(), e.getValue());
            }
        }
        return new DocumentDto(d.id(), d.kind(), d.version(), d.title(), body, d.changeSummary(), d.requiresReacceptance(),
            d.effectiveFrom(), d.publishedAt(), d.updatedAt());
    }
}
