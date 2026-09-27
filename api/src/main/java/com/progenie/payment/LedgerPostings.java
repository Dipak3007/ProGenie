package com.progenie.payment;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.progenie.booking.BookingPayments.Payable;

/**
 * Pure double-entry rules (unit tested). Debits always equal credits for a booking payment.
 *
 * <pre>
 * Online payment:  CUSTOMER D total  = PLATFORM_COMMISSION C commission + GENIE_EARNINGS C share + TIPS C tip
 * Cash payment:    GENIE_CASH D total (the Genie holds the cash) with the same credits
 * </pre>
 * A Genie's wallet = credits − debits over GENIE_EARNINGS, TIPS, GENIE_CASH, PAYOUT and SETTLEMENT:
 * after a cash job it is −commission (the Genie owes it); after an online job it is +share + tip.
 */
public final class LedgerPostings {

    private LedgerPostings() {
    }

    public record Entry(UUID bookingId, UUID genieId, String account, String direction, BigDecimal amount,
                        String description, UUID payoutId) {
    }

    public static List<Entry> bookingPaid(Payable b, boolean cash) {
        BigDecimal share = b.serviceAmount().add(b.extraAmount()).add(b.travelFee()).subtract(b.commissionAmount());
        List<Entry> entries = new ArrayList<>();
        if (cash) {
            add(entries, b.bookingId(), b.genieId(), "GENIE_CASH", "D", b.totalAmount(), "Cash collected by Genie for " + b.bookingRef());
        } else {
            add(entries, b.bookingId(), null, "CUSTOMER", "D", b.totalAmount(), "Customer paid online for " + b.bookingRef());
        }
        add(entries, b.bookingId(), null, "PLATFORM_COMMISSION", "C", b.commissionAmount(), "Commission on " + b.bookingRef());
        add(entries, b.bookingId(), b.genieId(), "GENIE_EARNINGS", "C", share, "Service, travel and extras for " + b.bookingRef());
        add(entries, b.bookingId(), b.genieId(), "TIPS", "C", b.tipAmount(), "Tip for " + b.bookingRef());
        return entries;
    }

    /** The late-cancellation fee is paid by the customer and goes entirely to the Genie. */
    public static List<Entry> cancellationFeePaid(Payable b) {
        List<Entry> entries = new ArrayList<>();
        add(entries, b.bookingId(), null, "CUSTOMER", "D", b.cancellationFee(), "Late cancellation fee for " + b.bookingRef());
        add(entries, b.bookingId(), b.genieId(), "GENIE_EARNINGS", "C", b.cancellationFee(), "Late cancellation fee for " + b.bookingRef());
        return entries;
    }

    /**
     * A refund of {@code genieShare + platformShare} to the customer (design doc 16.3).
     * <pre>
     * CUSTOMER C refund = GENIE_EARNINGS D (Genie part minus commission) + PLATFORM_COMMISSION D (commission reversed)
     *                   + PLATFORM_GOODWILL D (ProGenie's part)
     * </pre>
     * The commission reversed is the Genie part × (commission in the payment ÷ payment amount), so a full refund
     * charged to the Genie undoes exactly what the payment posted.
     */
    public static List<Entry> refund(UUID bookingId, UUID genieId, String bookingRef, BigDecimal genieShare,
                                     BigDecimal platformShare, BigDecimal paymentAmount, BigDecimal commissionInPayment) {
        List<Entry> entries = new ArrayList<>();
        BigDecimal total = genieShare.add(platformShare);
        add(entries, bookingId, null, "CUSTOMER", "C", total, "Refund to customer for " + bookingRef);
        BigDecimal commissionBack = BigDecimal.ZERO;
        if (genieShare.signum() > 0 && commissionInPayment != null && commissionInPayment.signum() > 0
            && paymentAmount.signum() > 0) {
            commissionBack = genieShare.multiply(commissionInPayment).divide(paymentAmount, 2, RoundingMode.HALF_UP);
        }
        add(entries, bookingId, genieId, "GENIE_EARNINGS", "D", genieShare.subtract(commissionBack),
            "Refund recovered from Genie for " + bookingRef);
        add(entries, bookingId, null, "PLATFORM_COMMISSION", "D", commissionBack, "Commission reversed for " + bookingRef);
        add(entries, bookingId, null, "PLATFORM_GOODWILL", "D", platformShare, "Goodwill refund for " + bookingRef);
        return entries;
    }

    /** Money sent to the Genie reduces what the platform owes them. */
    public static Entry payout(UUID genieId, BigDecimal amount, UUID payoutId, String reference) {
        return new Entry(null, genieId, "PAYOUT", "D", amount, "Payout " + (reference == null ? "" : reference).trim(), payoutId);
    }

    /** Money a Genie paid the platform to clear commission dues from cash jobs. */
    public static Entry settlement(UUID genieId, BigDecimal amount, String reference) {
        return new Entry(null, genieId, "SETTLEMENT", "C", amount, "Commission settlement " + (reference == null ? "" : reference).trim(), null);
    }

    public static BigDecimal debits(List<Entry> entries) {
        return sum(entries, "D");
    }

    public static BigDecimal credits(List<Entry> entries) {
        return sum(entries, "C");
    }

    private static BigDecimal sum(List<Entry> entries, String direction) {
        return entries.stream().filter(e -> e.direction().equals(direction)).map(Entry::amount)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static void add(List<Entry> entries, UUID bookingId, UUID genieId, String account, String direction,
                            BigDecimal amount, String description) {
        if (amount != null && amount.signum() > 0) {
            entries.add(new Entry(bookingId, genieId, account, direction, amount, description, null));
        }
    }
}
