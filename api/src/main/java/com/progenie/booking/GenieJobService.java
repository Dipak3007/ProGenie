package com.progenie.booking;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

import com.progenie.booking.BookingDtos.BookingDetailDto;
import com.progenie.booking.BookingDtos.CompleteRequest;
import com.progenie.booking.BookingQueryService.Viewer;
import com.progenie.booking.domain.Booking;
import com.progenie.booking.domain.BookingStatus;
import com.progenie.booking.domain.PaymentMethod;
import com.progenie.provider.GenieProfileService;
import com.progenie.legal.ConsentService;
import com.progenie.shared.config.AppProperties;
import com.progenie.shared.error.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/** Genie-side job operations: accept, decline, cancel, start (with the customer's code), complete. */
@Service
public class GenieJobService {

    private static final Logger log = LoggerFactory.getLogger(GenieJobService.class);

    private final BookingSupport support;
    private final BookingQueryService queries;
    private final GenieProfileService genies;
    private final AppProperties.Booking rules;
    private final Clock clock;
    private final ConsentService consents;

    public GenieJobService(BookingSupport support, BookingQueryService queries, GenieProfileService genies,
                           AppProperties props, Clock clock, ConsentService consents) {
        this.consents = consents;
        this.support = support;
        this.queries = queries;
        this.genies = genies;
        this.rules = props.booking();
        this.clock = clock;
    }

    @Transactional
    public BookingDetailDto accept(UUID genieId, UUID bookingId) {
        consents.requireNoPending(genieId, "GENIE");
        Booking b = support.lockForGenie(bookingId, genieId);
        BookingStatus from = b.getStatus();
        b.accept(clock.instant());
        support.transitioned(b, from, genieId, "GENIE", BookingEvent.Type.ACCEPTED, null, false);
        return queries.detail(bookingId, Viewer.GENIE, genieId);
    }

    @Transactional
    public BookingDetailDto decline(UUID genieId, UUID bookingId, String reason) {
        Booking b = support.lockForGenie(bookingId, genieId);
        BookingStatus from = b.getStatus();
        b.decline(reason.trim());
        support.transitioned(b, from, genieId, "GENIE", BookingEvent.Type.DECLINED, reason.trim(), false);
        return queries.detail(bookingId, Viewer.GENIE, genieId);
    }

    /**
     * Cancelling an accepted job hurts customers, so it is counted; crossing the threshold within the
     * window flags the Genie for an admin to review. The customer never pays a fee in this case.
     */
    @Transactional
    public BookingDetailDto cancel(UUID genieId, UUID bookingId, String reason) {
        Booking b = support.lockForGenie(bookingId, genieId);
        if (b.getStatus() != BookingStatus.ACCEPTED) {
            throw ApiException.unprocessable("INVALID_STATE", "Only accepted jobs can be cancelled; decline new requests instead");
        }
        String why = StringUtils.hasText(reason) ? reason.trim() : "Cancelled by Genie";
        b.cancel(clock.instant(), genieId, why, BigDecimal.ZERO);
        support.transitioned(b, BookingStatus.ACCEPTED, genieId, "GENIE", BookingEvent.Type.CANCELLED, why, false);
        genies.recordCancellation(genieId);
        int recent = queries.recentGenieCancellations(genieId, rules.genieCancellationFlagWindow());
        if (recent >= rules.genieCancellationFlagThreshold()) {
            genies.flag(genieId, recent + " cancellations of accepted jobs in the last "
                + rules.genieCancellationFlagWindow().toDays() + " days");
            log.warn("Genie {} flagged after {} cancellations", genieId, recent);
        }
        return queries.detail(bookingId, Viewer.GENIE, genieId);
    }

    /** Wrong codes are counted even though the request fails, hence noRollbackFor. */
    @Transactional(noRollbackFor = ApiException.class)
    public BookingDetailDto start(UUID genieId, UUID bookingId, String code) {
        Booking b = support.lockForGenie(bookingId, genieId);
        Instant now = clock.instant();
        if (b.getStatus() == BookingStatus.ACCEPTED && !BookingPolicy.canStart(b.getSlotStart(), now, rules.startEarlyWindow())) {
            throw ApiException.unprocessable("TOO_EARLY",
                "You can start at most " + rules.startEarlyWindow().toMinutes() + " minutes before the slot");
        }
        BookingStatus from = b.getStatus();
        b.start(now, BookingSupport.startCodeMatches(b, code));
        support.transitioned(b, from, genieId, "GENIE", BookingEvent.Type.STARTED, null, false);
        return queries.detail(bookingId, Viewer.GENIE, genieId);
    }

    @Transactional
    public BookingDetailDto complete(UUID genieId, UUID bookingId, CompleteRequest req) {
        Booking b = support.lockForGenie(bookingId, genieId);
        BigDecimal extra = req.extraAmount() == null ? BigDecimal.ZERO : req.extraAmount();
        if (extra.signum() > 0 && !StringUtils.hasText(req.extraNote())) {
            throw ApiException.badRequest("EXTRA_NOTE_REQUIRED", "Say what the extra charge is for (e.g. parts used)");
        }
        BookingStatus from = b.getStatus();
        b.complete(clock.instant(), extra, StringUtils.hasText(req.extraNote()) ? req.extraNote().trim() : null,
            req.cashCollected());
        genies.recordCompletedJob(genieId);
        // For cash the payment module records the payment and ledger in this same transaction.
        support.transitioned(b, from, genieId, "GENIE", BookingEvent.Type.COMPLETED, null,
            req.cashCollected() && b.getPaymentMethod() == PaymentMethod.CASH);
        return queries.detail(bookingId, Viewer.GENIE, genieId);
    }
}
