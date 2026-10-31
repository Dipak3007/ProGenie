package com.progenie.payment;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.progenie.payment.EarningsStatements.EntryLine;
import com.progenie.payment.EarningsStatements.JobLine;
import com.progenie.payment.EarningsStatements.PayoutRow;
import com.progenie.payment.EarningsStatements.Statement;
import com.progenie.payment.EarningsStatements.SummaryLine;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType1Font;

/**
 * The earnings statement as an A4 landscape PDF (PDFBox, standard Helvetica fonts like {@link ReceiptPdf}). Tables
 * continue on new pages with their header repeated; every page has a footer with the page number.
 */
final class StatementPdf {

    private static final Color NAVY = new Color(0x13, 0x1A, 0x2E);
    private static final Color INDIGO = new Color(0x4F, 0x46, 0xE5);
    private static final Color GOLD = new Color(0xF5, 0xB3, 0x01);
    private static final Color MUTED = new Color(0x6B, 0x72, 0x80);
    private static final Color LINE = new Color(0xE5, 0xE7, 0xEB);
    private static final Color HEAD_BG = new Color(0xF4, 0xF5, 0xFB);
    private static final PDFont REGULAR = PDType1Font.HELVETICA;
    private static final PDFont BOLD = PDType1Font.HELVETICA_BOLD;
    private static final float MARGIN = 40;
    private static final float BOTTOM = 50;
    private static final float ROW = 15;
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH);

    /** A table column: header, share of the width, right-aligned (numbers) or not. */
    private record Col(String title, float weight, boolean right) {
    }

    private final PDDocument pdf;
    private final Statement s;
    private final float width;
    private final float height;
    private PDPageContentStream cs;
    private float y;
    /** The table being drawn, so its header repeats after a page break. */
    private List<Col> currentCols;

    private StatementPdf(PDDocument pdf, Statement s) {
        this.pdf = pdf;
        this.s = s;
        PDRectangle landscape = new PDRectangle(PDRectangle.A4.getHeight(), PDRectangle.A4.getWidth());
        this.width = landscape.getWidth();
        this.height = landscape.getHeight();
    }

    static byte[] render(Statement s) {
        try (PDDocument pdf = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDDocumentInformation info = pdf.getDocumentInformation();
            info.setTitle("ProGenie earnings statement " + s.month());
            info.setAuthor("ProGenie");
            info.setCreator("ProGenie");
            StatementPdf w = new StatementPdf(pdf, s);
            w.write();
            w.footers();
            pdf.save(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException("Could not render the earnings statement", e);
        }
    }

    private void write() throws IOException {
        newPage(true);
        float right = width - MARGIN;

        // who and when
        text(BOLD, 13, NAVY, MARGIN, y, s.genieName());
        textRight(REGULAR, 9.5f, MUTED, right, y, "Generated " + s.generatedAt().format(DAY));
        y -= 15;
        text(REGULAR, 9.5f, MUTED, MARGIN, y, "Genie ID " + s.genieId() + "   |   Amounts in " + s.currency());
        y -= 26;

        // summary box
        float boxTop = y + 12;
        List<String[]> lines = new ArrayList<>();
        lines.add(new String[] {"Opening balance on " + s.from().format(DAY), money(s.opening())});
        for (SummaryLine l : s.summary()) {
            lines.add(new String[] {l.label(), signed(l.amount())});
        }
        float boxHeight = 22 + lines.size() * ROW + 26;
        cs.setNonStrokingColor(HEAD_BG);
        cs.addRect(MARGIN, boxTop - boxHeight, 420, boxHeight);
        cs.fill();
        text(BOLD, 9, MUTED, MARGIN + 12, y, "SUMMARY");
        y -= 18;
        for (String[] l : lines) {
            text(REGULAR, 10, NAVY, MARGIN + 12, y, l[0]);
            textRight(REGULAR, 10, NAVY, MARGIN + 408, y, l[1]);
            y -= ROW;
        }
        rule(MARGIN + 12, MARGIN + 408, y + 9);
        y -= 4;
        text(BOLD, 11, INDIGO, MARGIN + 12, y, "Closing balance on " + s.to().format(DAY));
        textRight(BOLD, 11, INDIGO, MARGIN + 408, y, money(s.closing()));
        float noteY = boxTop - 6;
        for (String n : List.of(
            "How to read the balance",
            "Positive: ProGenie owes you; it is paid out weekly.",
            "Negative: you owe ProGenie commission from cash jobs.",
            "",
            "You earned = service + extras + travel fee + instant fee",
            "+ tip - commission - refunds recovered.",
            "Cash jobs: you already hold the money, so only the",
            "commission moves your balance.")) {
            text(n.startsWith("How") ? BOLD : REGULAR, 9, MUTED, MARGIN + 445, noteY, n);
            noteY -= 13;
        }
        y = Math.min(boxTop - boxHeight, noteY) - 28;

        // jobs
        List<Col> jobCols = List.of(new Col("Date", 1.1f, false), new Col("Booking", 1.4f, false),
            new Col("Service", 2.2f, false), new Col("Paid by", 0.8f, false), new Col("Service", 1f, true),
            new Col("Extras", 0.8f, true), new Col("Travel", 0.8f, true), new Col("Instant", 0.8f, true),
            new Col("Tip", 0.8f, true), new Col("Commission", 1f, true), new Col("Refunds", 0.9f, true),
            new Col("You earned", 1.1f, true));
        section("Jobs completed (" + s.jobs().size() + ")");
        if (s.jobs().isEmpty()) {
            empty("No jobs completed in this period.");
        } else {
            header(jobCols);
            for (JobLine j : s.jobs()) {
                row(jobCols, false, j.date().format(DAY), j.bookingRef(), j.serviceName(),
                    j.paymentMethod().equals("CASH") ? "Cash" : "Online", plain(j.serviceAmount()),
                    plain(j.extraAmount()), plain(j.travelFee()), plain(j.instantFee()), plain(j.tip()),
                    plain(j.commission()), plain(j.refundDeduction()), plain(j.earned()));
            }
            JobLine t = s.jobTotals();
            row(jobCols, true, "Total", "", "", "", plain(t.serviceAmount()), plain(t.extraAmount()),
                plain(t.travelFee()), plain(t.instantFee()), plain(t.tip()), plain(t.commission()),
                plain(t.refundDeduction()), plain(t.earned()));
        }
        y -= 18;

        // payouts
        List<Col> payoutCols = List.of(new Col("Date", 1.2f, false), new Col("For the week", 2.2f, false),
            new Col("Reference", 2.2f, false), new Col("Status", 1f, false), new Col("Amount", 1.2f, true));
        section("Payouts (" + s.payouts().size() + ")");
        if (s.payouts().isEmpty()) {
            empty("No payouts in this period.");
        } else {
            header(payoutCols);
            for (PayoutRow p : s.payouts()) {
                row(payoutCols, false, (p.paidAt() != null ? p.paidAt() : p.createdAt()).format(DAY),
                    p.periodStart().format(DAY) + " - " + p.periodEnd().format(DAY),
                    p.reference() == null ? "-" : p.reference(), p.status(), plain(p.amount()));
            }
        }
        y -= 18;

        // wallet entries
        List<Col> entryCols = List.of(new Col("Date", 1.1f, false), new Col("Booking", 1.4f, false),
            new Col("Description", 4.4f, false), new Col("Credit", 1f, true), new Col("Debit", 1f, true),
            new Col("Balance", 1.1f, true));
        section("Wallet entries (" + s.entries().size() + ")");
        header(entryCols);
        row(entryCols, false, s.from().format(DAY), "", "Opening balance", "", "", plain(s.opening()));
        for (EntryLine e : s.entries()) {
            row(entryCols, false, e.date().format(DAY), e.bookingRef() == null ? "" : e.bookingRef(), e.description(),
                e.credit().signum() == 0 ? "" : plain(e.credit()), e.debit().signum() == 0 ? "" : plain(e.debit()),
                plain(e.balance()));
        }
        row(entryCols, true, s.to().format(DAY), "", "Closing balance", "", "", plain(s.closing()));
        cs.close();
    }

    // ------------------------------------------------------------------ layout

    private void newPage(boolean first) throws IOException {
        if (cs != null) {
            cs.close();
        }
        PDPage page = new PDPage(new PDRectangle(width, height));
        pdf.addPage(page);
        cs = new PDPageContentStream(pdf, page);
        float right = width - MARGIN;
        if (first) {
            cs.setNonStrokingColor(NAVY);
            cs.addRect(0, height - 80, width, 80);
            cs.fill();
            text(REGULAR, 22, Color.WHITE, MARGIN, height - 48, "Pro");
            text(BOLD, 22, GOLD, MARGIN + REGULAR.getStringWidth("Pro") / 1000 * 22, height - 48, "Genie");
            text(REGULAR, 8.5f, new Color(0xA5, 0xB4, 0xFC), MARGIN, height - 64, "PROFESSIONAL GENIE");
            textRight(BOLD, 15, Color.WHITE, right, height - 44, "EARNINGS STATEMENT");
            textRight(REGULAR, 10, Color.WHITE, right, height - 62, s.monthLabel() + "   |   " + s.periodLabel());
            y = height - 110;
        } else {
            text(BOLD, 10, NAVY, MARGIN, height - 34, "ProGenie earnings statement");
            textRight(REGULAR, 9, MUTED, right, height - 34, s.genieName() + "   |   " + s.monthLabel());
            rule(MARGIN, right, height - 42);
            y = height - 62;
        }
    }

    private void ensure(float needed, List<Col> repeatHeader) throws IOException {
        if (y - needed < BOTTOM) {
            newPage(false);
            if (repeatHeader != null) {
                header(repeatHeader);
            }
        }
    }

    private void section(String title) throws IOException {
        ensure(ROW * 4, null);
        text(BOLD, 11, NAVY, MARGIN, y, title);
        y -= 16;
    }

    private void empty(String message) throws IOException {
        text(REGULAR, 9.5f, MUTED, MARGIN, y, message);
        y -= ROW;
    }

    private void header(List<Col> cols) throws IOException {
        currentCols = cols;
        float right = width - MARGIN;
        cs.setNonStrokingColor(HEAD_BG);
        cs.addRect(MARGIN, y - 5, right - MARGIN, 17);
        cs.fill();
        cells(cols, BOLD, 8, MUTED, cols.stream().map(c -> c.title().toUpperCase(Locale.ROOT)).toArray(String[]::new));
        y -= 18;
    }

    private void row(List<Col> cols, boolean bold, String... values) throws IOException {
        ensure(ROW, currentCols);
        cells(cols, bold ? BOLD : REGULAR, 8.5f, NAVY, values);
        y -= 5;
        rule(MARGIN, width - MARGIN, y);
        y -= ROW - 5;
    }

    private void cells(List<Col> cols, PDFont font, float size, Color color, String... values) throws IOException {
        float total = 0;
        for (Col c : cols) {
            total += c.weight();
        }
        float usable = width - 2 * MARGIN - 8;
        float x = MARGIN + 4;
        for (int i = 0; i < cols.size(); i++) {
            Col c = cols.get(i);
            float w = usable * c.weight() / total;
            String v = fit(values[i], font, size, w - 6);
            if (c.right()) {
                textRight(font, size, color, x + w - 2, y, v);
            } else {
                text(font, size, color, x, y, v);
            }
            x += w;
        }
    }

    /** Page footers, added once the number of pages is known. */
    private void footers() throws IOException {
        int pages = pdf.getNumberOfPages();
        for (int i = 0; i < pages; i++) {
            PDPage page = pdf.getPage(i);
            try (PDPageContentStream f = new PDPageContentStream(pdf, page, PDPageContentStream.AppendMode.APPEND, true)) {
                cs = f;
                text(REGULAR, 7.5f, MUTED, MARGIN, 26,
                    "Computer-generated statement from the ProGenie ledger; it needs no signature.");
                textRight(REGULAR, 7.5f, MUTED, width - MARGIN, 26, "Page " + (i + 1) + " of " + pages);
            }
        }
    }

    // ------------------------------------------------------------------ drawing helpers

    private static String money(BigDecimal amount) {
        return ReceiptPdf.money(amount);
    }

    private static String signed(BigDecimal amount) {
        return (amount.signum() >= 0 ? "+ " : "- ") + ReceiptPdf.money(amount.abs());
    }

    private static String plain(BigDecimal amount) {
        return ReceiptPdf.money(amount).substring(4); // without "Rs. "
    }

    private static String fit(String s, PDFont font, float size, float maxWidth) throws IOException {
        String t = ReceiptPdf.safe(s);
        if (font.getStringWidth(t) / 1000 * size <= maxWidth) {
            return t;
        }
        while (t.length() > 1 && font.getStringWidth(t + ".") / 1000 * size > maxWidth) {
            t = t.substring(0, t.length() - 1);
        }
        return t + ".";
    }

    private void rule(float from, float to, float atY) throws IOException {
        cs.setStrokingColor(LINE);
        cs.setLineWidth(0.6f);
        cs.moveTo(from, atY);
        cs.lineTo(to, atY);
        cs.stroke();
    }

    private void text(PDFont font, float size, Color color, float x, float atY, String s) throws IOException {
        cs.beginText();
        cs.setFont(font, size);
        cs.setNonStrokingColor(color);
        cs.newLineAtOffset(x, atY);
        cs.showText(ReceiptPdf.safe(s));
        cs.endText();
    }

    private void textRight(PDFont font, float size, Color color, float right, float atY, String s) throws IOException {
        String t = ReceiptPdf.safe(s);
        float w = font.getStringWidth(t) / 1000 * size;
        text(font, size, color, right - w, atY, t);
    }
}
