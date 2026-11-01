package com.progenie.shared.util;

import java.math.BigDecimal;

import org.jspecify.annotations.Nullable;

/**
 * Genies' ratings of customers (Phase 3.5A). Customers never see them. Genies see a customer's average once it rests
 * on {@link #MIN_SHOWN} ratings; admins always, with a flag when a customer needs attention.
 */
public final class CustomerRatingRules {

    /** Genies see the average from this many ratings. */
    public static final int MIN_SHOWN = 3;
    /** Flag: an average under {@link #FLAG_BELOW} from at least this many ratings... */
    public static final int FLAG_MIN_RATINGS = 5;
    public static final BigDecimal FLAG_BELOW = new BigDecimal("3");
    /** ...or this many NOT_AT_HOME / UNSAFE reports in the last 90 days. */
    public static final int FLAG_RED_TAGS = 2;

    private CustomerRatingRules() {
    }

    /** Why the customer is flagged for admin, or null. */
    public static @Nullable String flagReason(@Nullable BigDecimal avg, long count, long redTags90d) {
        if (redTags90d >= FLAG_RED_TAGS) {
            return redTags90d + " \"not at home\" or \"unsafe\" reports from Genies in the last 90 days";
        }
        if (avg != null && count >= FLAG_MIN_RATINGS && avg.compareTo(FLAG_BELOW) < 0) {
            return "Average rating " + avg.stripTrailingZeros().toPlainString() + " from " + count + " Genies";
        }
        return null;
    }
}
