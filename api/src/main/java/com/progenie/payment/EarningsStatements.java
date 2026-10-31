package com.progenie.payment;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import com.progenie.shared.config.AppProperties;
import com.progenie.shared.config.QueryConstants;
import com.progenie.shared.error.ApiException;
import com.progenie.shared.util.QueryUtil;
import com.progenie.shared.util.Times;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Monthly earnings statement for a Genie (Phase 3A-6), as CSV or PDF: any past calendar month, or the current month
 * so far. Built from the bookings and the ledger, so it always agrees with the wallet:
 * closing balance = opening balance + credits − debits of the wallet entries in the month.
 *
 * <ul>
 *   <li>Jobs completed in the month: service amount, extras, travel fee, instant fee (Genie Now, Phase 3C), tip,
 *       commission, refunds recovered for that job, and what the Genie earned</li>
 *   <li>Payouts made in the month</li>
 *   <li>Every wallet entry in the month with the running balance</li>
 * </ul>
 */
@Service
public class EarningsStatements {

    private static final Logger log = LoggerFactory.getLogger(EarningsStatements.class);
    private static final DateTimeFormatter MONTH_NAME = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.ENGLISH);
    static final YearMonth FIRST_MONTH = YearMonth.of(2024, 1);

    public enum Format { CSV, PDF }

    record Genie(String fullName, OffsetDateTime createdAt, @Nullable OffsetDateTime approvedAt) {
    }

    record JobRow(String bookingRef, OffsetDateTime completedAt, String serviceName, String paymentMethod,
                  BigDecimal serviceAmount, BigDecimal extraAmount, BigDecimal travelFee, BigDecimal tipAmount,
                  BigDecimal commissionAmount, BigDecimal refundDeduction, BigDecimal instantFee) {
    }

    /** One job; {@code earned} = service + extras + travel + instant fee + tip − commission − refunds. */
    record JobLine(String bookingRef, LocalDate date, String serviceName, String paymentMethod, BigDecimal serviceAmount,
                   BigDecimal extraAmount, BigDecimal travelFee, BigDecimal instantFee, BigDecimal tip,
                   BigDecimal commission, BigDecimal refundDeduction, BigDecimal earned) {
    }

    record PayoutRow(LocalDate periodStart, LocalDate periodEnd, BigDecimal amount, String status,
                     @Nullable String reference, OffsetDateTime createdAt, @Nullable OffsetDateTime paidAt) {
    }

    record EntryRow(OffsetDateTime createdAt, @Nullable String bookingRef, String account, String direction,
                    BigDecimal amount, @Nullable String description) {
    }

    /** A wallet entry with the balance after it. */
    record EntryLine(LocalDate date, @Nullable String bookingRef, String description, BigDecimal credit,
                     BigDecimal debit, BigDecimal balance) {
    }

    /** One line of the summary: what moved the balance, signed (+ credit, − debit). */
    record SummaryLine(String label, BigDecimal amount) {
    }

    record Statement(UUID genieId, String genieName, YearMonth month, LocalDate from, LocalDate to, boolean partial,
                     BigDecimal opening, BigDecimal closing, List<SummaryLine> summary, List<JobLine> jobs,
                     JobLine jobTotals, List<PayoutRow> payouts, List<EntryLine> entries, String currency,
                     OffsetDateTime generatedAt) {

        String monthLabel() {
            return month.format(MONTH_NAME);
        }

        String periodLabel() {
            DateTimeFormatter d = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH);
            return from.format(d) + " - " + to.format(d) + (partial ? " (so far)" : "");
        }
    }

    /** The finished file. */
    public record StatementFile(String fileName, String contentType, byte[] content) {

        /** The file as a download reply (never cached). */
        public ResponseEntity<byte[]> asDownload() {
            return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(contentType))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(fileName).build().toString())
                .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                .body(content);
        }
    }

    private final JdbcClient jdbc;
    private final Clock clock;
    private final ZoneId zone;
    private final String currency;

    public EarningsStatements(JdbcClient jdbc, Clock clock, AppProperties props) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.zone = ZoneId.of(props.timezone());
        this.currency = props.payments().currency();
    }

    /** @param month "YYYY-MM"; @param requestedBy "GENIE" or "ADMIN", for the log line */
    @Transactional(readOnly = true)
    public StatementFile file(UUID genieId, String month, Format format, String requestedBy) {
        Statement s = build(genieId, parseMonth(month));
        log.info("Earnings statement {} ({}) for Genie {} downloaded by {}", s.month(), format, genieId, requestedBy);
        String base = "progenie-earnings-" + s.month();
        return format == Format.CSV
            ? new StatementFile(base + ".csv", "text/csv;charset=UTF-8", csv(s))
            : new StatementFile(base + ".pdf", "application/pdf", StatementPdf.render(s));
    }

    /** "csv" or "pdf", any case. */
    public static Format format(String value) {
        try {
            return Format.valueOf(value == null ? "" : value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("INVALID_FORMAT", "Format must be csv or pdf");
        }
    }

    YearMonth parseMonth(String month) {
        YearMonth m;
        try {
            m = YearMonth.parse(month == null ? "" : month.trim());
        } catch (DateTimeParseException e) {
            throw ApiException.badRequest("INVALID_MONTH", "Month must look like 2026-10");
        }
        if (m.isAfter(YearMonth.now(clock.withZone(zone)))) {
            throw ApiException.badRequest("INVALID_MONTH", "That month has not started yet");
        }
        if (m.isBefore(FIRST_MONTH)) {
            throw ApiException.badRequest("INVALID_MONTH", "No statements before " + FIRST_MONTH);
        }
        return m;
    }

    Statement build(UUID genieId, YearMonth month) {
        Genie genie = jdbc.sql(QueryUtil.get(QueryConstants.GET_STATEMENT_GENIE)).param("g", genieId)
            .query(Genie.class).optional()
            .orElseThrow(() -> ApiException.notFound("GENIE_NOT_FOUND", "Genie not found"));
        Instant now = clock.instant();
        Instant start = month.atDay(1).atStartOfDay(zone).toInstant();
        Instant monthEnd = month.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant();
        boolean partial = now.isBefore(monthEnd);
        Instant end = partial ? now : monthEnd;

        BigDecimal opening = jdbc.sql(QueryUtil.get(QueryConstants.GET_STATEMENT_BALANCE_BEFORE))
            .param("g", genieId).param("at", Times.odt(start)).query(BigDecimal.class).single();

        List<JobLine> jobs = jdbc.sql(QueryUtil.get(QueryConstants.GET_STATEMENT_JOBS))
            .param("g", genieId).param("from", Times.odt(start)).param("to", Times.odt(end))
            .query(JobRow.class).list().stream()
            .map(this::jobLine)
            .toList();

        List<PayoutRow> payouts = jdbc.sql(QueryUtil.get(QueryConstants.GET_STATEMENT_PAYOUTS))
            .param("g", genieId).param("from", Times.odt(start)).param("to", Times.odt(end))
            .query(PayoutRow.class).list();

        List<EntryRow> rows = jdbc.sql(QueryUtil.get(QueryConstants.GET_STATEMENT_ENTRIES))
            .param("g", genieId).param("from", Times.odt(start)).param("to", Times.odt(end))
            .query(EntryRow.class).list();
        List<EntryLine> entries = new ArrayList<>(rows.size());
        Map<String, BigDecimal> moved = new LinkedHashMap<>();
        BigDecimal balance = opening;
        for (EntryRow e : rows) {
            boolean credit = "C".equals(e.direction());
            balance = credit ? balance.add(e.amount()) : balance.subtract(e.amount());
            entries.add(new EntryLine(e.createdAt().atZoneSameInstant(zone).toLocalDate(), e.bookingRef(),
                e.description() == null ? e.account() : e.description(),
                credit ? e.amount() : BigDecimal.ZERO, credit ? BigDecimal.ZERO : e.amount(), balance));
            moved.merge(summaryLabel(e.account(), e.direction()), credit ? e.amount() : e.amount().negate(), BigDecimal::add);
        }
        List<SummaryLine> summary = moved.entrySet().stream()
            .map(m -> new SummaryLine(m.getKey(), m.getValue())).toList();

        return new Statement(genieId, genie.fullName(), month, month.atDay(1),
            partial ? LocalDate.ofInstant(now, zone) : month.atEndOfMonth(), partial, opening, balance, summary, jobs,
            totals(jobs), payouts, entries, currency, Times.odt(now));
    }

    private JobLine jobLine(JobRow r) {
        BigDecimal instantFee = r.instantFee(); // Genie Now (Phase 3C-9)
        BigDecimal earned = r.serviceAmount().add(r.extraAmount()).add(r.travelFee()).add(instantFee).add(r.tipAmount())
            .subtract(r.commissionAmount()).subtract(r.refundDeduction());
        return new JobLine(r.bookingRef(), r.completedAt().atZoneSameInstant(zone).toLocalDate(), r.serviceName(),
            r.paymentMethod(), r.serviceAmount(), r.extraAmount(), r.travelFee(), instantFee, r.tipAmount(),
            r.commissionAmount(), r.refundDeduction(), earned);
    }

    private static JobLine totals(List<JobLine> jobs) {
        BigDecimal[] t = new BigDecimal[8];
        java.util.Arrays.fill(t, BigDecimal.ZERO);
        for (JobLine j : jobs) {
            t[0] = t[0].add(j.serviceAmount());
            t[1] = t[1].add(j.extraAmount());
            t[2] = t[2].add(j.travelFee());
            t[3] = t[3].add(j.instantFee());
            t[4] = t[4].add(j.tip());
            t[5] = t[5].add(j.commission());
            t[6] = t[6].add(j.refundDeduction());
            t[7] = t[7].add(j.earned());
        }
        return new JobLine("Total (" + jobs.size() + (jobs.size() == 1 ? " job)" : " jobs)"), null, "", "",
            t[0], t[1], t[2], t[3], t[4], t[5], t[6], t[7]);
    }

    /** Plain words for what each kind of wallet entry means to the Genie. */
    static String summaryLabel(String account, String direction) {
        boolean credit = "C".equals(direction);
        return switch (account) {
            case "GENIE_EARNINGS" -> credit ? "Earnings (service, travel, extras, cancellation fees)" : "Refunds recovered";
            case "TIPS" -> credit ? "Tips" : "Tips reversed";
            case "GENIE_CASH" -> credit ? "Cash adjustments" : "Cash you collected from customers";
            case "PAYOUT" -> credit ? "Payouts returned" : "Payouts sent to you";
            case "SETTLEMENT" -> credit ? "Commission you paid to ProGenie" : "Settlements reversed";
            default -> account + (credit ? " (credit)" : " (debit)");
        };
    }

    // ------------------------------------------------------------------ CSV

    static byte[] csv(Statement s) {
        StringBuilder out = new StringBuilder("﻿"); // BOM, so Excel reads UTF-8
        row(out, "ProGenie earnings statement");
        row(out, "Genie", s.genieName());
        row(out, "Month", s.monthLabel());
        row(out, "Period", s.periodLabel());
        row(out, "Currency", s.currency());
        row(out, "Opening balance", amount(s.opening()));
        for (SummaryLine l : s.summary()) {
            row(out, l.label(), amount(l.amount()));
        }
        row(out, "Closing balance", amount(s.closing()));
        row(out, "Generated", s.generatedAt().toString());
        out.append("\r\n");
        row(out, "JOBS COMPLETED");
        row(out, "Date", "Booking", "Service", "Paid by", "Service amount", "Extras", "Travel fee", "Instant fee", "Tip",
            "Commission", "Refunds recovered", "You earned");
        for (JobLine j : s.jobs()) {
            jobRow(out, j);
        }
        jobRow(out, s.jobTotals());
        out.append("\r\n");
        row(out, "PAYOUTS");
        row(out, "Date", "Period from", "Period to", "Reference", "Status", "Amount");
        for (PayoutRow p : s.payouts()) {
            row(out, String.valueOf((p.paidAt() != null ? p.paidAt() : p.createdAt()).toLocalDate()),
                p.periodStart().toString(), p.periodEnd().toString(), p.reference() == null ? "" : p.reference(),
                p.status(), amount(p.amount()));
        }
        out.append("\r\n");
        row(out, "WALLET ENTRIES");
        row(out, "Date", "Booking", "Description", "Credit", "Debit", "Balance");
        row(out, "", "", "Opening balance", "", "", amount(s.opening()));
        for (EntryLine e : s.entries()) {
            row(out, e.date().toString(), e.bookingRef() == null ? "" : e.bookingRef(), e.description(),
                e.credit().signum() == 0 ? "" : amount(e.credit()), e.debit().signum() == 0 ? "" : amount(e.debit()),
                amount(e.balance()));
        }
        return out.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static void jobRow(StringBuilder out, JobLine j) {
        row(out, j.date() == null ? "" : j.date().toString(), j.bookingRef(), j.serviceName(), j.paymentMethod(),
            amount(j.serviceAmount()), amount(j.extraAmount()), amount(j.travelFee()), amount(j.instantFee()),
            amount(j.tip()), amount(j.commission()), amount(j.refundDeduction()), amount(j.earned()));
    }

    static String amount(BigDecimal v) {
        return v.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString();
    }

    private static void row(StringBuilder out, String... cells) {
        for (int i = 0; i < cells.length; i++) {
            if (i > 0) {
                out.append(',');
            }
            out.append(cell(cells[i]));
        }
        out.append("\r\n");
    }

    /** RFC 4180 quoting; text that a spreadsheet would run as a formula is prefixed with a quote. */
    static String cell(String v) {
        String s = v == null ? "" : v;
        if (!s.isEmpty() && "=+@\t\r".indexOf(s.charAt(0)) >= 0
            || (s.startsWith("-") && !s.matches("-\\d+(\\.\\d+)?"))) {
            s = "'" + s;
        }
        if (s.contains(",") || s.contains("\"") || s.contains("\n") || s.contains("\r")) {
            s = "\"" + s.replace("\"", "\"\"") + "\"";
        }
        return s;
    }
}
