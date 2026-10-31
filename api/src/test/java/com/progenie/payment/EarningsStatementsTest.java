package com.progenie.payment;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.progenie.payment.EarningsStatements.EntryLine;
import com.progenie.payment.EarningsStatements.JobLine;
import com.progenie.payment.EarningsStatements.PayoutRow;
import com.progenie.payment.EarningsStatements.Statement;
import com.progenie.payment.EarningsStatements.SummaryLine;
import com.progenie.shared.error.ApiException;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EarningsStatementsTest {

    @Test
    void csvCellsAreQuotedAndFormulasDefused() {
        assertThat(EarningsStatements.cell("Fan repair")).isEqualTo("Fan repair");
        assertThat(EarningsStatements.cell("Tap, kitchen")).isEqualTo("\"Tap, kitchen\"");
        assertThat(EarningsStatements.cell("Say \"hi\"")).isEqualTo("\"Say \"\"hi\"\"\"");
        assertThat(EarningsStatements.cell("=HYPERLINK(\"x\")")).startsWith("\"'=HYPERLINK");
        assertThat(EarningsStatements.cell("@cmd")).isEqualTo("'@cmd");
        assertThat(EarningsStatements.cell("-12.50")).isEqualTo("-12.50");
        assertThat(EarningsStatements.cell("-cmd")).isEqualTo("'-cmd");
    }

    @Test
    void walletEntriesAreDescribedInPlainWords() {
        assertThat(EarningsStatements.summaryLabel("GENIE_CASH", "D")).isEqualTo("Cash you collected from customers");
        assertThat(EarningsStatements.summaryLabel("PAYOUT", "D")).isEqualTo("Payouts sent to you");
        assertThat(EarningsStatements.summaryLabel("GENIE_EARNINGS", "D")).isEqualTo("Refunds recovered");
    }

    @Test
    void formatIsCsvOrPdfInAnyCase() {
        assertThat(EarningsStatements.format("csv")).isEqualTo(EarningsStatements.Format.CSV);
        assertThat(EarningsStatements.format(" PDF ")).isEqualTo(EarningsStatements.Format.PDF);
        assertThatThrownBy(() -> EarningsStatements.format("xlsx")).isInstanceOf(ApiException.class);
    }

    @Test
    void csvHasEverySectionAndTheBalances() {
        String csv = new String(EarningsStatements.csv(sample(3)), StandardCharsets.UTF_8);
        assertThat(csv).startsWith("﻿ProGenie earnings statement\r\n")
            .contains("Opening balance,-150.00", "Closing balance,620.00", "JOBS COMPLETED",
                "PG-2026-000001,Fan repair,CASH", "Total (3 jobs)", "PAYOUTS", "WALLET ENTRIES", "\"Tap, kitchen\"");
    }

    @Test
    void pdfRunsOverSeveralPagesWithFooters() throws Exception {
        byte[] pdf = StatementPdf.render(sample(60));
        Path out = Path.of("target/statement-sample.pdf");
        Files.write(out, pdf); // open it to eyeball the layout
        try (PDDocument doc = PDDocument.load(pdf)) {
            assertThat(doc.getNumberOfPages()).isGreaterThan(1);
            String text = new PDFTextStripper().getText(doc);
            assertThat(text).contains("EARNINGS STATEMENT", "October 2026", "Ravi Patel", "Closing balance",
                "Page 1 of " + doc.getNumberOfPages(), "PG-2026-000060");
        }
    }

    static Statement sample(int jobs) {
        List<JobLine> lines = new ArrayList<>();
        List<EntryLine> entries = new ArrayList<>();
        for (int i = 1; i <= jobs; i++) {
            lines.add(new JobLine("PG-2026-%06d".formatted(i), LocalDate.of(2026, 10, 1 + i % 28),
                i % 2 == 0 ? "Tap, kitchen" : "Fan repair", i % 2 == 0 ? "ONLINE" : "CASH",
                new BigDecimal("399.00"), BigDecimal.ZERO, new BigDecimal("40.00"), BigDecimal.ZERO,
                new BigDecimal("20.00"), new BigDecimal("59.85"), BigDecimal.ZERO, new BigDecimal("399.15")));
            entries.add(new EntryLine(LocalDate.of(2026, 10, 1 + i % 28), "PG-2026-%06d".formatted(i),
                "Service, travel and extras for PG-2026-%06d".formatted(i), new BigDecimal("379.15"), BigDecimal.ZERO,
                new BigDecimal(i * 10)));
        }
        JobLine total = new JobLine("Total (" + jobs + " jobs)", null, "", "", BigDecimal.ONE, BigDecimal.ZERO,
            BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.TEN);
        return new Statement(UUID.fromString("1af855e6-31a8-5caa-bffa-6f864b9644ff"), "Ravi Patel", YearMonth.of(2026, 10),
            LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31), false, new BigDecimal("-150.00"), new BigDecimal("620.00"),
            List.of(new SummaryLine("Earnings (service, travel, extras, cancellation fees)", new BigDecimal("1200.00")),
                new SummaryLine("Cash you collected from customers", new BigDecimal("-430.00"))),
            lines, total,
            List.of(new PayoutRow(LocalDate.of(2026, 10, 5), LocalDate.of(2026, 10, 11), new BigDecimal("500.00"), "PAID",
                "UTR123", OffsetDateTime.of(2026, 10, 13, 4, 0, 0, 0, ZoneOffset.UTC), OffsetDateTime.of(2026, 10, 13, 5, 0, 0, 0, ZoneOffset.UTC))),
            entries, "INR", OffsetDateTime.of(2026, 11, 1, 6, 0, 0, 0, ZoneOffset.UTC));
    }
}
