package com.progenie.legal;

import com.progenie.shared.error.ApiException;

/** The four policy documents and their URL slugs. */
public enum LegalKind {
    TERMS("terms"), PRIVACY("privacy"), REFUNDS("refunds"), GENIE_AGREEMENT("genie-agreement");

    private final String slug;

    LegalKind(String slug) {
        this.slug = slug;
    }

    public String slug() {
        return slug;
    }

    /** Accepts "terms", "genie-agreement" or the enum name. */
    public static LegalKind parse(String value) {
        if (value != null) {
            String v = value.trim();
            for (LegalKind k : values()) {
                if (k.slug.equalsIgnoreCase(v) || k.name().equalsIgnoreCase(v.replace('-', '_'))) {
                    return k;
                }
            }
        }
        throw ApiException.notFound("LEGAL_DOCUMENT_NOT_FOUND", "Unknown document " + value);
    }
}
