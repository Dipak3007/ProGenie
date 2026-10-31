package com.progenie.payment;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.progenie.messaging.delivery.Channel;
import com.progenie.messaging.delivery.MessageTemplate;
import com.progenie.messaging.delivery.Messenger;
import com.progenie.payment.DocumentNumbers.Series;
import com.progenie.payment.PaymentDtos.PaymentReceived;
import com.progenie.payment.PaymentDtos.RefundProcessed;
import com.progenie.payment.ReceiptPdf.Line;
import com.progenie.shared.config.AppProperties;
import com.progenie.shared.config.CompanyProperties;
import com.progenie.shared.config.QueryConstants;
import com.progenie.shared.error.ApiException;
import com.progenie.shared.storage.FileStorage;
import com.progenie.shared.util.QueryUtil;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * A receipt for every successful payment (online or cash) and a credit note for every processed refund,
 * numbered without gaps per financial year, rendered to PDF, stored, and emailed to the customer's verified
 * address. Issued inside the payment's transaction, so a rolled-back payment has no receipt.
 */
@Service
public class ReceiptService {

    private static final Logger log = LoggerFactory.getLogger(ReceiptService.class);
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH);
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("d MMM yyyy, h:mm a", Locale.ENGLISH);

    public record ReceiptDto(UUID id, String number, String kind, UUID bookingId, @Nullable UUID paymentId, @Nullable UUID refundId,
                             BigDecimal amount, OffsetDateTime issuedAt) {
    }

    /** Everything a document shows, read in one query. */
    record Source(UUID bookingId, String bookingRef, UUID customerId, String customerName, String customerEmail,
                  String customerPhone, String genieName, String service, OffsetDateTime slotStart, String area,
                  String city, String pincode, BigDecimal serviceAmount, BigDecimal travelFee, BigDecimal extraAmount,
                  String extraNote, BigDecimal tipAmount, BigDecimal cancellationFee, BigDecimal discountAmount,
                  String couponCode, BigDecimal instantFee) {
    }

    record PaymentInfo(UUID id, String purpose, String method, String provider, String providerRef, BigDecimal amount,
                       OffsetDateTime updatedAt) {
    }

    record ReceiptRow(UUID id, String number, String kind, UUID bookingId, UUID paymentId, UUID refundId,
                      UUID customerId, BigDecimal amount, String tax, String fileKey, OffsetDateTime issuedAt) {
    }

    private final JdbcClient jdbc;
    private final DocumentNumbers numbers;
    private final FileStorage storage;
    private final Messenger messenger;
    private final CompanyProperties company;
    private final TaxProperties tax;
    private final JsonMapper json;
    private final ZoneId zone;
    private final Clock clock;

    public ReceiptService(JdbcClient jdbc, DocumentNumbers numbers, FileStorage storage, Messenger messenger,
                          CompanyProperties company, TaxProperties tax, JsonMapper json, AppProperties props, Clock clock) {
        this.jdbc = jdbc;
        this.numbers = numbers;
        this.storage = storage;
        this.messenger = messenger;
        this.company = company;
        this.tax = tax;
        this.json = json;
        this.zone = ZoneId.of(props.timezone());
        this.clock = clock;
    }

    // ------------------------------------------------------------------ issuing (same transaction as the payment)

    @EventListener
    public void onPayment(PaymentReceived e) {
        if (exists(QueryConstants.EXISTS_RECEIPT_FOR_PAYMENT, e.paymentId())) {
            return;   // never burn a number on a duplicate
        }
        LocalDate today = LocalDate.now(clock.withZone(zone));
        String number = numbers.next(Series.RECEIPT, today);
        String taxJson = taxJson(e.amount());
        UUID id = jdbc.sql(QueryUtil.get(QueryConstants.INSERT_RECEIPT_PAYMENT))
            .param("n", number).param("b", e.bookingId()).param("p", e.paymentId()).param("c", e.customerId())
            .param("amount", e.amount()).param("tax", taxJson).param("gstin", tax.active() ? tax.gstin() : null)
            .query(UUID.class).single();
        ReceiptRow r = row(id);
        String key = storeSafely(r);
        Source s = source(e.bookingId());
        Map<String, String> params = new LinkedHashMap<>();
        params.put("amount", e.amount().toPlainString());
        params.put("ref", e.bookingRef());
        params.put("number", number);
        params.put("service", s.service());
        params.put("method", methodLabel(e.method()));
        params.put("link", "/account/bookings/" + e.bookingId());
        params.put("linkLabel", "View booking");
        messenger.toUser(e.customerId(), MessageTemplate.PAYMENT_RECEIPT, Set.of(Channel.EMAIL), params, "receipt:" + id,
            key == null ? null : new Messenger.Attachment(key, fileName(r)));
        log.info("Issued receipt {} for {}", number, e.bookingRef());
    }

    @EventListener
    public void onRefund(RefundProcessed e) {
        if (exists(QueryConstants.EXISTS_RECEIPT_FOR_REFUND, e.refundId())) {
            return;
        }
        LocalDate today = LocalDate.now(clock.withZone(zone));
        String number = numbers.next(Series.CREDIT_NOTE, today);
        UUID id = jdbc.sql(QueryUtil.get(QueryConstants.INSERT_RECEIPT_CREDIT_NOTE))
            .param("n", number).param("b", e.bookingId()).param("p", e.paymentId()).param("r", e.refundId())
            .param("c", e.customerId()).param("amount", e.amount()).param("tax", taxJson(e.amount()))
            .param("gstin", tax.active() ? tax.gstin() : null)
            .query(UUID.class).single();
        ReceiptRow r = row(id);
        String key = storeSafely(r);
        Source s = source(e.bookingId());
        String refundText = "GATEWAY".equals(e.method())
            ? "It goes back to your original payment method, usually within 5-7 working days."
            : "We sent it to you directly; check your UPI or bank account.";
        Map<String, String> params = new LinkedHashMap<>();
        params.put("amount", e.amount().toPlainString());
        params.put("ref", e.bookingRef());
        params.put("number", number);
        params.put("service", s.service());
        params.put("refundText", refundText);
        params.put("link", "/account/bookings/" + e.bookingId());
        params.put("linkLabel", "View booking");
        messenger.toUser(e.customerId(), MessageTemplate.REFUND_PROCESSED, Set.of(Channel.EMAIL), params,
            "credit-note:" + id, key == null ? null : new Messenger.Attachment(key, fileName(r)));
        log.info("Issued credit note {} for {}", number, e.bookingRef());
    }

    private boolean exists(String queryKey, UUID id) {
        return jdbc.sql(QueryUtil.get(queryKey)).param("id", id)
            .query(Boolean.class).single();
    }

    // ------------------------------------------------------------------ reads

    @Transactional(readOnly = true)
    public List<ReceiptDto> forBooking(UUID bookingId, UUID customerIdOrNull) {
        return jdbc.sql(QueryUtil.get(QueryConstants.GET_RECEIPT_FOR_BOOKING))
            .param("b", bookingId).param("c", customerIdOrNull)
            .query(ReceiptDto.class).list();
    }

    /** Receipts and credit notes of one customer (data export). */
    @Transactional(readOnly = true)
    public List<ReceiptDto> forCustomer(UUID customerId) {
        return jdbc.sql(QueryUtil.get(QueryConstants.GET_RECEIPT_FOR_CUSTOMER))
            .param("c", customerId).query(ReceiptDto.class).list();
    }

    public record PdfFile(String fileName, byte[] content) {
    }

    /** The PDF, for its customer or an admin. Rendered again if the stored file is missing. */
    @Transactional
    public PdfFile pdf(UUID receiptId, UUID userId, boolean admin) {
        ReceiptRow r = jdbc.sql(QueryUtil.get(QueryConstants.GET_RECEIPT_ROW)).param("id", receiptId).query(ReceiptRow.class).optional()
            .filter(x -> admin || x.customerId().equals(userId))
            .orElseThrow(() -> ApiException.notFound("RECEIPT_NOT_FOUND", "Receipt not found"));
        if (r.fileKey() != null) {
            try (InputStream in = storage.get(r.fileKey())) {
                return new PdfFile(fileName(r), in.readAllBytes());
            } catch (ApiException | IOException missing) {
                log.warn("Receipt file {} missing; rendering again", r.fileKey());
            }
        }
        byte[] pdf = render(r);
        store(r, pdf);
        return new PdfFile(fileName(r), pdf);
    }

    /**
     * A short-lived download link (presigned URL on S3) for its customer or an admin. The PDF is rendered and stored
     * first if it is missing, so the link always works.
     */
    @Transactional
    public FileStorage.FileLink link(UUID receiptId, UUID userId, boolean admin, boolean inline) {
        ReceiptRow r = jdbc.sql(QueryUtil.get(QueryConstants.GET_RECEIPT_ROW)).param("id", receiptId).query(ReceiptRow.class).optional()
            .filter(x -> admin || x.customerId().equals(userId))
            .orElseThrow(() -> ApiException.notFound("RECEIPT_NOT_FOUND", "Receipt not found"));
        String key = r.fileKey();
        if (key == null || !storage.exists(key)) {
            key = store(r, render(r));
        }
        return storage.link(key, fileName(r), "application/pdf", inline);
    }

    // ------------------------------------------------------------------ rendering

    private ReceiptRow row(UUID id) {
        return jdbc.sql(QueryUtil.get(QueryConstants.GET_RECEIPT_ROW)).param("id", id).query(ReceiptRow.class).single();
    }

    /** A PDF problem must never undo the payment: the file is rendered again on first download. */
    private String storeSafely(ReceiptRow r) {
        try {
            return store(r, render(r));
        } catch (RuntimeException ex) {
            log.error("Could not render receipt {}", r.number(), ex);
            return null;
        }
    }

    private String store(ReceiptRow r, byte[] pdf) {
        String key = "receipts/" + r.issuedAt().atZoneSameInstant(zone).getYear() + "/" + r.id() + ".pdf";
        storage.put(key, new ByteArrayInputStream(pdf), pdf.length, "application/pdf");
        jdbc.sql(QueryUtil.get(QueryConstants.UPDATE_RECEIPT_FILE_KEY)).param("k", key).param("id", r.id()).update();
        return key;
    }

    byte[] render(ReceiptRow r) {
        Source s = source(r.bookingId());
        PaymentInfo p = r.paymentId() == null ? null : jdbc.sql(QueryUtil.get(QueryConstants.GET_RECEIPT_PAYMENT_DETAILS))
            .param("id", r.paymentId()).query(PaymentInfo.class).optional().orElse(null);
        boolean credit = "CREDIT_NOTE".equals(r.kind());
        boolean taxed = r.tax() != null && !r.tax().isBlank() && !"null".equals(r.tax());
        String title = credit ? "Credit note" : taxed ? "Tax invoice" : "Payment receipt";

        List<String> from = new ArrayList<>();
        addIf(from, company.address());
        addIf(from, company.city());
        if (taxed) {
            addIf(from, "GSTIN: " + tax.gstin());
        }
        addIf(from, company.supportEmail());
        List<String> to = new ArrayList<>();
        addIf(to, join(s.area(), s.city(), s.pincode()));
        addIf(to, s.customerPhone());
        addIf(to, s.customerEmail());

        List<String[]> facts = new ArrayList<>();
        facts.add(new String[] {"Booking", s.bookingRef()});
        facts.add(new String[] {"Service date", s.slotStart() == null ? "-" : DATE_TIME.format(s.slotStart().atZoneSameInstant(zone))});
        facts.add(new String[] {"Service", s.service()});
        facts.add(new String[] {"Genie", s.genieName()});
        if (p != null) {
            facts.add(new String[] {"Paid by", methodLabel(p.method()) + (p.providerRef() == null ? "" : " (" + p.providerRef() + ")")});
        }
        if (taxed && tax.sac() != null) {
            facts.add(new String[] {"SAC", tax.sac()});
        }

        List<Line> lines = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        if (credit) {
            record RefundInfo(String reason, String method, String manualReference) {
            }
            RefundInfo ri = jdbc.sql(QueryUtil.get(QueryConstants.GET_RECEIPT_REFUND_DETAILS))
                .param("id", r.refundId()).query(RefundInfo.class).single();
            String original = jdbc.sql(QueryUtil.get(QueryConstants.GET_RECEIPT_NUMBER_FOR_PAYMENT))
                .param("p", r.paymentId()).query(String.class).optional().orElse(null);
            lines.add(new Line("Refund", ri.reason(), r.amount()));
            if (original != null) {
                notes.add("Against receipt " + original + ".");
            }
            notes.add("GATEWAY".equals(ri.method())
                ? "Refunded to the original payment method; banks usually take 5-7 working days."
                : "Refunded directly" + (ri.manualReference() == null ? "." : " (reference " + ri.manualReference() + ")."));
        } else if (p != null && "CANCELLATION_FEE".equals(p.purpose())) {
            lines.add(new Line("Late cancellation fee", "Paid to your Genie for the reserved slot", r.amount()));
        } else {
            lines.add(new Line(s.service(), null, s.serviceAmount()));
            if (s.travelFee() != null && s.travelFee().signum() > 0) {
                lines.add(new Line("Travel fee", null, s.travelFee()));
            }
            if (s.instantFee() != null && s.instantFee().signum() > 0) {
                lines.add(new Line("Genie Now fee", "Instant booking", s.instantFee()));
            }
            if (s.extraAmount() != null && s.extraAmount().signum() > 0) {
                lines.add(new Line("Parts and extras", s.extraNote(), s.extraAmount()));
            }
            if (s.tipAmount() != null && s.tipAmount().signum() > 0) {
                lines.add(new Line("Tip for your Genie", null, s.tipAmount()));
            }
            if (s.discountAmount() != null && s.discountAmount().signum() > 0) {
                lines.add(new Line("Coupon " + (s.couponCode() == null ? "" : s.couponCode()), "Discount paid by ProGenie",
                    s.discountAmount().negate()));
            }
            notes.add("Thank you for choosing ProGenie. Questions? Write to " + orDash(company.supportEmail()) + ".");
        }
        List<Line> taxLines = new ArrayList<>();
        if (taxed) {
            Map<?, ?> t = json.readValue(r.tax(), Map.class);
            taxLines.add(new Line("Taxable value", null, new BigDecimal(t.get("taxable").toString())));
            taxLines.add(new Line("CGST @ " + half(t.get("rate")) + "%", null, new BigDecimal(t.get("cgst").toString())));
            taxLines.add(new Line("SGST @ " + half(t.get("rate")) + "%", null, new BigDecimal(t.get("sgst").toString())));
        }
        ReceiptPdf.Doc doc = new ReceiptPdf.Doc(title, r.number(), DATE.format(r.issuedAt().atZoneSameInstant(zone)),
            company.legalNameOr("ProGenie"), from, s.customerName(), to, facts, lines,
            credit ? "Total refunded" : "Total paid", r.amount(), taxLines, notes);
        return ReceiptPdf.render(doc);
    }

    private Source source(UUID bookingId) {
        return jdbc.sql(QueryUtil.get(QueryConstants.GET_RECEIPT_SOURCE))
            .param("id", bookingId).query(Source.class).single();
    }

    /** Tax lines when GST is on: amounts are tax-inclusive; CGST and SGST split the tax. */
    String taxJson(BigDecimal amount) {
        if (!tax.active()) {
            return null;
        }
        BigDecimal rate = tax.rate();
        BigDecimal taxable = amount.multiply(BigDecimal.valueOf(100))
            .divide(BigDecimal.valueOf(100).add(rate), 2, RoundingMode.HALF_UP);
        BigDecimal total = amount.subtract(taxable);
        BigDecimal cgst = total.divide(BigDecimal.valueOf(2), 2, RoundingMode.HALF_UP);
        Map<String, Object> t = new LinkedHashMap<>();
        t.put("rate", rate);
        t.put("taxable", taxable);
        t.put("cgst", cgst);
        t.put("sgst", total.subtract(cgst));
        t.put("sac", tax.sac());
        return json.writeValueAsString(t);
    }

    private static String half(Object rate) {
        return new BigDecimal(rate.toString()).divide(BigDecimal.valueOf(2), 2, RoundingMode.HALF_UP).stripTrailingZeros()
            .toPlainString();
    }

    static String fileName(ReceiptRow r) {
        return ("CREDIT_NOTE".equals(r.kind()) ? "credit-note-" : "receipt-") + r.number().replace('/', '-') + ".pdf";
    }

    static String methodLabel(String method) {
        return switch (method == null ? "" : method) {
            case "CASH" -> "Cash";
            case "ONLINE" -> "Online (UPI / card / netbanking)";
            case "UPI" -> "UPI";
            case "CARD" -> "Card";
            default -> method == null ? "-" : method.charAt(0) + method.substring(1).toLowerCase(Locale.ROOT);
        };
    }

    private static void addIf(List<String> list, String value) {
        if (value != null && !value.isBlank() && !value.endsWith(": null")) {
            list.add(value);
        }
    }

    private static String join(String... parts) {
        List<String> out = new ArrayList<>();
        for (String p : parts) {
            if (p != null && !p.isBlank()) {
                out.add(p);
            }
        }
        return String.join(", ", out);
    }

    private static String orDash(String s) {
        return s == null || s.isBlank() ? "-" : s;
    }
}
