package com.progenie.shared.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code progenie.company.*}: the business behind ProGenie, printed on receipts and filled into the legal
 * pages' placeholders. Leave a value empty and the placeholder (e.g. "[Company legal name]") stays visible.
 */
@ConfigurationProperties(prefix = "progenie.company")
public record CompanyProperties(String legalName, String address, String city, String supportEmail, String supportPhone,
                                String grievanceName, String grievanceEmail, String website) {

    public String legalNameOr(String fallback) {
        return blank(legalName) ? fallback : legalName;
    }

    static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
