package com.progenie.payment;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType1Font;

/**
 * Renders receipts and credit notes as a one-page A4 PDF with Apache PDFBox. Uses the standard Helvetica fonts,
 * so text is limited to Latin characters: the rupee sign prints as "Rs." and other characters as "?".
 */
final class ReceiptPdf {

    record Line(String label, String detail, BigDecimal amount) {
    }

    record Doc(String title, String number, String date, String companyName, List<String> companyLines,
               String billedTo, List<String> billedToLines, List<String[]> facts, List<Line> lines, String totalLabel,
               BigDecimal total, List<Line> taxLines, List<String> notes) {
    }

    private static final Color NAVY = new Color(0x13, 0x1A, 0x2E);
    private static final Color INDIGO = new Color(0x4F, 0x46, 0xE5);
    private static final Color GOLD = new Color(0xF5, 0xB3, 0x01);
    private static final Color MUTED = new Color(0x6B, 0x72, 0x80);
    private static final Color LINE = new Color(0xE5, 0xE7, 0xEB);
    private static final PDFont REGULAR = PDType1Font.HELVETICA;
    private static final PDFont BOLD = PDType1Font.HELVETICA_BOLD;
    private static final float MARGIN = 50;

    private ReceiptPdf() {
    }

    static byte[] render(Doc d) {
        try (PDDocument pdf = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDPage page = new PDPage(PDRectangle.A4);
            pdf.addPage(page);
            PDDocumentInformation info = pdf.getDocumentInformation();
            info.setTitle(d.title() + " " + d.number());
            info.setAuthor(d.companyName());
            info.setCreator("ProGenie");
            float width = page.getMediaBox().getWidth();
            float right = width - MARGIN;
            try (PDPageContentStream cs = new PDPageContentStream(pdf, page)) {
                // header band
                float top = page.getMediaBox().getHeight();
                cs.setNonStrokingColor(NAVY);
                cs.addRect(0, top - 90, width, 90);
                cs.fill();
                text(cs, REGULAR, 24, Color.WHITE, MARGIN, top - 55, "Pro");
                text(cs, BOLD, 24, GOLD, MARGIN + REGULAR.getStringWidth("Pro") / 1000 * 24, top - 55, "Genie");
                text(cs, REGULAR, 9, new Color(0xA5, 0xB4, 0xFC), MARGIN, top - 72, "PROFESSIONAL GENIE");
                textRight(cs, BOLD, 16, Color.WHITE, right, top - 50, d.title().toUpperCase(Locale.ROOT));
                textRight(cs, REGULAR, 10, Color.WHITE, right, top - 68, d.number() + "   |   " + d.date());

                float y = top - 125;
                // from / to
                text(cs, BOLD, 10, MUTED, MARGIN, y, "FROM");
                text(cs, BOLD, 10, MUTED, width / 2, y, "BILLED TO");
                y -= 16;
                float yLeft = y;
                text(cs, BOLD, 11, NAVY, MARGIN, yLeft, d.companyName());
                for (String l : d.companyLines()) {
                    yLeft -= 14;
                    text(cs, REGULAR, 9.5f, NAVY, MARGIN, yLeft, l);
                }
                float yRight = y;
                text(cs, BOLD, 11, NAVY, width / 2, yRight, d.billedTo());
                for (String l : d.billedToLines()) {
                    yRight -= 14;
                    text(cs, REGULAR, 9.5f, NAVY, width / 2, yRight, l);
                }
                y = Math.min(yLeft, yRight) - 28;

                // facts grid (2 columns)
                for (int i = 0; i < d.facts().size(); i++) {
                    String[] f = d.facts().get(i);
                    float x = i % 2 == 0 ? MARGIN : width / 2;
                    text(cs, REGULAR, 8.5f, MUTED, x, y, f[0].toUpperCase(Locale.ROOT));
                    text(cs, BOLD, 10, NAVY, x, y - 13, f[1]);
                    if (i % 2 == 1 || i == d.facts().size() - 1) {
                        y -= 34;
                    }
                }
                y -= 6;

                // lines table
                cs.setNonStrokingColor(new Color(0xF4, 0xF5, 0xFB));
                cs.addRect(MARGIN, y - 6, right - MARGIN, 20);
                cs.fill();
                text(cs, BOLD, 9, MUTED, MARGIN + 8, y, "DESCRIPTION");
                textRight(cs, BOLD, 9, MUTED, right - 8, y, "AMOUNT");
                y -= 26;
                for (Line l : d.lines()) {
                    text(cs, REGULAR, 10, NAVY, MARGIN + 8, y, l.label());
                    textRight(cs, REGULAR, 10, NAVY, right - 8, y, money(l.amount()));
                    if (l.detail() != null && !l.detail().isBlank()) {
                        y -= 12;
                        text(cs, REGULAR, 8.5f, MUTED, MARGIN + 8, y, l.detail());
                    }
                    y -= 10;
                    rule(cs, MARGIN, right, y);
                    y -= 16;
                }
                for (Line t : d.taxLines()) {
                    text(cs, REGULAR, 9.5f, MUTED, width / 2, y, t.label());
                    textRight(cs, REGULAR, 9.5f, MUTED, right - 8, y, money(t.amount()));
                    y -= 16;
                }
                cs.setNonStrokingColor(INDIGO);
                cs.addRect(width / 2 - 8, y - 10, right - width / 2 + 8, 28);
                cs.fill();
                text(cs, BOLD, 11, Color.WHITE, width / 2, y, d.totalLabel());
                textRight(cs, BOLD, 12, Color.WHITE, right - 8, y, money(d.total()));
                y -= 44;

                for (String n : d.notes()) {
                    text(cs, REGULAR, 9, MUTED, MARGIN, y, n);
                    y -= 14;
                }
                text(cs, REGULAR, 8, MUTED, MARGIN, 40, "This is a computer-generated document and needs no signature.");
            }
            pdf.save(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException("Could not render " + d.number(), e);
        }
    }

    /** Indian digit grouping: Rs. 1,23,456.50. */
    static String money(BigDecimal amount) {
        String plain = (amount == null ? BigDecimal.ZERO : amount).setScale(2, java.math.RoundingMode.HALF_UP).toPlainString();
        boolean negative = plain.startsWith("-");
        String digits = negative ? plain.substring(1) : plain;
        String whole = digits.substring(0, digits.indexOf('.'));
        String fraction = digits.substring(digits.indexOf('.'));
        StringBuilder grouped = new StringBuilder();
        int len = whole.length();
        if (len <= 3) {
            grouped.append(whole);
        } else {
            String head = whole.substring(0, len - 3);
            for (int i = 0; i < head.length(); i++) {
                if (i > 0 && (head.length() - i) % 2 == 0) {
                    grouped.append(',');
                }
                grouped.append(head.charAt(i));
            }
            grouped.append(',').append(whole.substring(len - 3));
        }
        return "Rs. " + (negative ? "-" : "") + grouped + fraction;
    }

    private static void rule(PDPageContentStream cs, float from, float to, float y) throws IOException {
        cs.setStrokingColor(LINE);
        cs.setLineWidth(0.7f);
        cs.moveTo(from, y);
        cs.lineTo(to, y);
        cs.stroke();
    }

    private static void text(PDPageContentStream cs, PDFont font, float size, Color color, float x, float y, String s)
        throws IOException {
        cs.beginText();
        cs.setFont(font, size);
        cs.setNonStrokingColor(color);
        cs.newLineAtOffset(x, y);
        cs.showText(safe(s));
        cs.endText();
    }

    private static void textRight(PDPageContentStream cs, PDFont font, float size, Color color, float right, float y,
                                  String s) throws IOException {
        String t = safe(s);
        float w = font.getStringWidth(t) / 1000 * size;
        text(cs, font, size, color, right - w, y, t);
    }

    /** Keeps what the standard fonts can encode (WinAnsi, roughly Latin-1). */
    static String safe(String s) {
        if (s == null) {
            return "";
        }
        String t = s.replace("₹", "Rs. ").replace('–', '-').replace('—', '-').replace('’', '\'').replace('‘', '\'')
            .replace('“', '"').replace('”', '"').replace('…', '.').replace('\t', ' ').replace('\n', ' ');
        StringBuilder out = new StringBuilder(t.length());
        for (char c : t.toCharArray()) {
            out.append((c >= 32 && c <= 126) || (c >= 160 && c <= 255) ? c : '?');
        }
        return out.length() > 110 ? out.substring(0, 109) + "." : out.toString();
    }
}
