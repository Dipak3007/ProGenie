package com.progenie.booking;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import com.progenie.booking.BookingDtos.BookingItemDto;
import com.progenie.pricing.PriceCalculator.PriceLine;
import com.progenie.shared.config.QueryConstants;
import com.progenie.shared.util.QueryUtil;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * The services on a booking (Phase 3.5C, table {@code booking_items}): 1–5 lines from the same Genie, each with its
 * price, quantity, duration, commission and share of the coupon discount as they were at booking time. The booking's
 * own amounts are the totals of its lines.
 */
@Component
class BookingItems {

    /** Longest label stored on the booking ({@code bookings.services_label}). */
    private static final int LABEL_MAX = 400;

    /** A stored line, with the internal amounts (commission) the DTO leaves out. */
    record Item(long id, int position, Long serviceId, String serviceName, int quantity, BigDecimal unitPrice,
                BigDecimal amount, int durationMinutes, BigDecimal commissionAmount, BigDecimal discountAmount) {

        int minutes() {
            return durationMinutes * quantity;
        }

        BookingItemDto dto() {
            return new BookingItemDto(id, serviceId, serviceName, quantity, unitPrice, amount, durationMinutes, discountAmount);
        }
    }

    private final JdbcClient jdbc;

    BookingItems(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** "Fan installation ×2 + Switchboard repair". */
    static String label(List<String> names, List<Integer> quantities) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < names.size(); i++) {
            if (i > 0) {
                sb.append(" + ");
            }
            sb.append(names.get(i));
            if (quantities.get(i) > 1) {
                sb.append(" ×").append(quantities.get(i));
            }
        }
        return sb.length() <= LABEL_MAX ? sb.toString() : sb.substring(0, LABEL_MAX - 1) + "…";
    }

    static String label(List<PriceLine> lines) {
        return label(lines.stream().map(PriceLine::serviceName).toList(), lines.stream().map(PriceLine::quantity).toList());
    }

    static String labelOf(List<Item> items) {
        return label(items.stream().map(Item::serviceName).toList(), items.stream().map(Item::quantity).toList());
    }

    /** Stores a new booking's lines; {@code discounts.get(i)} is line i's share of the coupon discount. */
    void insert(UUID bookingId, List<PriceLine> lines, List<BigDecimal> discounts) {
        for (int i = 0; i < lines.size(); i++) {
            PriceLine l = lines.get(i);
            jdbc.sql(QueryUtil.get(QueryConstants.INSERT_BOOKING_ITEM))
                .param("booking", bookingId).param("position", i + 1).param("service", l.serviceId())
                .param("name", l.serviceName()).param("quantity", l.quantity()).param("unitPrice", l.unitPrice())
                .param("amount", l.amount()).param("minutes", l.durationMinutes())
                .param("commission", l.commissionAmount()).param("discount", discounts.get(i))
                .update();
        }
    }

    /**
     * A line without a catalog service (Phase 3.5D): the inspection visit itself, or a custom line of a quote such as
     * "Replace 20 m of wiring".
     */
    void insertCustom(UUID bookingId, int position, String description, int quantity, BigDecimal unitPrice,
                      BigDecimal commission, int minutes) {
        BigDecimal amount = unitPrice.multiply(BigDecimal.valueOf(quantity)).setScale(2, java.math.RoundingMode.HALF_UP);
        jdbc.sql(QueryUtil.get(QueryConstants.INSERT_BOOKING_ITEM))
            .param("booking", bookingId).param("position", position).param("service", null)
            .param("name", description).param("quantity", quantity).param("unitPrice", unitPrice)
            .param("amount", amount).param("minutes", minutes)
            .param("commission", commission).param("discount", BigDecimal.ZERO)
            .update();
    }

    /** A line of a quoted job (Phase 3.5D): a catalog service at the quoted price. */
    void insertQuoted(UUID bookingId, int position, Long serviceId, String description, int quantity, BigDecimal unitPrice,
                      BigDecimal amount, BigDecimal commission, int minutes) {
        jdbc.sql(QueryUtil.get(QueryConstants.INSERT_BOOKING_ITEM))
            .param("booking", bookingId).param("position", position).param("service", serviceId)
            .param("name", description).param("quantity", quantity).param("unitPrice", unitPrice)
            .param("amount", amount).param("minutes", minutes)
            .param("commission", commission).param("discount", BigDecimal.ZERO)
            .update();
    }

    /** A free redo (complaint resolution): the original's lines at no charge. */
    void copyFree(UUID originalId, UUID redoId) {
        jdbc.sql(QueryUtil.get(QueryConstants.INSERT_BOOKING_ITEMS_COPY_FREE))
            .param("original", originalId).param("redo", redoId).update();
    }

    List<Item> list(UUID bookingId) {
        return jdbc.sql(QueryUtil.get(QueryConstants.GET_BOOKING_ITEMS)).param("booking", bookingId)
            .query(Item.class).list();
    }

    List<BookingItemDto> dtos(UUID bookingId) {
        return list(bookingId).stream().map(Item::dto).toList();
    }

    /** Removes a line and closes the gap in the positions. */
    void remove(UUID bookingId, long itemId) {
        jdbc.sql(QueryUtil.get(QueryConstants.DELETE_BOOKING_ITEM)).param("booking", bookingId).param("id", itemId).update();
        jdbc.sql(QueryUtil.get(QueryConstants.UPDATE_BOOKING_ITEMS_RENUMBER)).param("booking", bookingId).update();
    }

    /** Stores each remaining line's new share of the coupon discount (after a service was removed). */
    void updateDiscounts(List<Item> items, List<BigDecimal> discounts) {
        for (int i = 0; i < items.size(); i++) {
            jdbc.sql(QueryUtil.get(QueryConstants.UPDATE_BOOKING_ITEM_DISCOUNT))
                .param("id", items.get(i).id()).param("discount", discounts.get(i)).update();
        }
    }
}
