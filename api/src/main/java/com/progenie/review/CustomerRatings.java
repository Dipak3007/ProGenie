package com.progenie.review;

import java.time.OffsetDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.progenie.booking.BookingQueryService;
import com.progenie.booking.BookingQueryService.RatableBooking;
import com.progenie.shared.config.QueryConstants;
import com.progenie.shared.error.ApiException;
import com.progenie.shared.util.CustomerRatingRules;
import com.progenie.shared.util.QueryUtil;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Genies rate customers (Phase 3.5A): 1-5 stars and optional tags, right after completing the job, once per booking.
 * Never shown to the customer. Genies see a customer's average from {@link CustomerRatingRules#MIN_SHOWN} ratings;
 * admins see everything and a flag ({@link CustomerRatingRules#flagReason}).
 */
@Service
public class CustomerRatings {

    private static final Logger log = LoggerFactory.getLogger(CustomerRatings.class);

    public enum Tag { NOT_AT_HOME, UNSAFE, RUDE, GREAT }

    public record CustomerRatingRequest(@Min(1) @Max(5) int rating, @Size(max = 4) List<Tag> tags) {
    }

    public record CustomerRatingDto(UUID bookingId, int rating, List<String> tags, OffsetDateTime createdAt) {
    }

    record Stats(java.math.BigDecimal avg, long cnt, long red) {
    }

    private final JdbcClient jdbc;
    private final BookingQueryService bookings;

    public CustomerRatings(JdbcClient jdbc, BookingQueryService bookings) {
        this.jdbc = jdbc;
        this.bookings = bookings;
    }

    @Transactional
    public CustomerRatingDto rate(UUID genieId, UUID bookingId, CustomerRatingRequest req) {
        RatableBooking b = bookings.ratableByGenie(genieId, bookingId);
        Set<Tag> tags = req.tags() == null ? Set.of() : new LinkedHashSet<>(req.tags());
        if (tags.contains(Tag.GREAT) && (tags.contains(Tag.UNSAFE) || tags.contains(Tag.RUDE))) {
            throw ApiException.badRequest("CONFLICTING_TAGS", "\"Great\" can't go with \"unsafe\" or \"rude\"");
        }
        String[] tagNames = tags.stream().map(Enum::name).toArray(String[]::new);
        OffsetDateTime createdAt;
        try {
            createdAt = jdbc.sql(QueryUtil.get(QueryConstants.INSERT_CUSTOMER_RATING))
                .param("b", bookingId).param("c", b.customerId()).param("g", genieId)
                .param("rating", req.rating()).param("tags", String.join(",", tagNames))
                .query(OffsetDateTime.class).single();
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("ALREADY_RATED", "You have already rated this customer for this job");
        }
        Stats s = jdbc.sql(QueryUtil.get(QueryConstants.GET_CUSTOMER_RATING_STATS)).param("c", b.customerId())
            .query(Stats.class).single();
        String flag = CustomerRatingRules.flagReason(s.avg(), s.cnt(), s.red());
        log.info("Booking {}: Genie {} rated customer {} with {} stars {}", b.bookingRef(), genieId, b.customerId(),
            req.rating(), List.of(tagNames));
        if (flag != null) {
            log.warn("Customer {} needs attention: {}", b.customerId(), flag);
        }
        return new CustomerRatingDto(bookingId, req.rating(), List.of(tagNames), createdAt);
    }
}
