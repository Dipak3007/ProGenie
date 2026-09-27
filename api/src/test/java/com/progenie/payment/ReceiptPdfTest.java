package com.progenie.payment;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;

class ReceiptPdfTest {

    @Test
    void rendersAOnePageReceiptWithTheNumbersOnIt() throws Exception {
        ReceiptPdf.Doc doc = new ReceiptPdf.Doc("Payment receipt", "PG/2026-27/000001", "27 Sep 2026", "ProGenie",
            List.of("Ahmedabad"), "Asha Mehta", List.of("Navrangpura, Ahmedabad, 380009"),
            List.of(new String[] {"Booking", "PG-2026-000001"}, new String[] {"Genie", "Ravi Patel"}),
            List.of(new ReceiptPdf.Line("Fan repair", null, new BigDecimal("399.00")),
                new ReceiptPdf.Line("Travel fee", null, new BigDecimal("50.00"))),
            "Total paid", new BigDecimal("449.00"), List.of(), List.of("Thank you ₹"));
        byte[] pdf = ReceiptPdf.render(doc);
        assertThat(new String(pdf, 0, 5)).isEqualTo("%PDF-");
        try (PDDocument d = PDDocument.load(pdf)) {
            assertThat(d.getNumberOfPages()).isEqualTo(1);
            String text = new PDFTextStripper().getText(d);
            assertThat(text).contains("PG/2026-27/000001").contains("Rs. 449.00").contains("Fan repair").contains("Asha Mehta");
        }
    }

    @Test
    void textIsLimitedToWhatTheStandardFontsCanPrint() {
        assertThat(ReceiptPdf.safe("₹100 – ok")).isEqualTo("Rs. 100 - ok");
        assertThat(ReceiptPdf.safe("राम Patel")).isEqualTo("??? Patel");
        assertThat(ReceiptPdf.money(new BigDecimal("123456.5"))).isEqualTo("Rs. 1,23,456.50");
        assertThat(ReceiptPdf.money(new BigDecimal("12345678"))).isEqualTo("Rs. 1,23,45,678.00");
        assertThat(ReceiptPdf.money(new BigDecimal("999"))).isEqualTo("Rs. 999.00");
        assertThat(ReceiptPdf.money(new BigDecimal("1000"))).isEqualTo("Rs. 1,000.00");
    }
}
