package com.progenie.payment;

import java.math.BigDecimal;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code progenie.tax.*}. Off by default: documents are titled "Payment receipt" with no tax lines. With GST
 * enabled (GSTIN, SAC code and rate set) the same template becomes a "Tax invoice" with CGST/SGST lines; amounts
 * are treated as tax-inclusive. Which amount is taxable for a marketplace is a question for your CA.
 */
@ConfigurationProperties(prefix = "progenie.tax")
public record TaxProperties(@DefaultValue("false") boolean gstEnabled, String gstin, String sac,
                            @DefaultValue("18") BigDecimal rate) {

    public boolean active() {
        return gstEnabled && gstin != null && !gstin.isBlank();
    }
}
