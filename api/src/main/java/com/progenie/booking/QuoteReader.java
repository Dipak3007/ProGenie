package com.progenie.booking;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.progenie.booking.BookingDtos.QuoteDto;
import com.progenie.booking.BookingDtos.QuoteLineDto;
import com.progenie.booking.domain.Booking;
import com.progenie.shared.config.QueryConstants;
import com.progenie.shared.util.QueryUtil;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** Reads quotes for the booking pages (Phase 3.5D); {@link Quotes} changes them. */
@Component
class QuoteReader {

    private final JdbcClient jdbc;
    private final Clock clock;

    QuoteReader(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /** The quote after an inspection, or the one a job was made from; null when there is none. */
    @Nullable QuoteDto forBooking(UUID bookingId, String kind, @Nullable UUID quoteId) {
        Optional<Quotes.Quote> q = Booking.KIND_INSPECTION.equals(kind)
            ? jdbc.sql(QueryUtil.get(QueryConstants.GET_QUOTE_BY_INSPECTION)).param("id", bookingId).query(Quotes.Quote.class).optional()
            : quoteId == null ? Optional.empty()
            : jdbc.sql(QueryUtil.get(QueryConstants.GET_QUOTE_BY_ID)).param("id", quoteId).query(Quotes.Quote.class).optional();
        return q.map(x -> dto(Quotes.effective(x, clock.instant()))).orElse(null);
    }

    /** Whether the customer can still answer it (sent and not past its date). */
    static boolean open(@Nullable QuoteDto q, Instant now) {
        return q != null && "SENT".equals(q.status()) && now.isBefore(q.validUntil().toInstant());
    }

    List<Quotes.Line> lines(UUID quoteId) {
        return jdbc.sql(QueryUtil.get(QueryConstants.GET_QUOTE_LINES)).param("id", quoteId).query(Quotes.Line.class).list();
    }

    private QuoteDto dto(Quotes.Quote q) {
        BigDecimal credit = q.inspectionFeePaid().min(q.totalAmount()).setScale(2, RoundingMode.HALF_UP);
        List<QuoteLineDto> lines = lines(q.id()).stream()
            .map(l -> new QuoteLineDto(l.id(), l.serviceId(), l.description(), l.quantity(), l.unitPrice(), l.amount()))
            .toList();
        return new QuoteDto(q.id(), q.inspectionBookingId(), q.inspectionBookingRef(), q.status(), q.version(), lines,
            q.totalAmount(), credit, q.totalAmount().subtract(credit), q.durationMinutes(), q.note(), q.validUntil(),
            q.declineReason(), q.respondedAt(), q.jobBookingId(), q.jobBookingRef(), q.createdAt(), q.updatedAt());
    }
}
