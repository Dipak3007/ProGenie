package com.progenie.shared.util;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

/** Phase 3.5A: when admins see "needs attention" for a customer. */
class CustomerRatingRulesTest {

    @Test
    void aLowAverageNeedsFiveRatings() {
        assertThat(CustomerRatingRules.flagReason(new BigDecimal("2.4"), 4, 0)).isNull();
        assertThat(CustomerRatingRules.flagReason(new BigDecimal("2.4"), 5, 0)).isEqualTo("Average rating 2.4 from 5 Genies");
        assertThat(CustomerRatingRules.flagReason(new BigDecimal("3.0"), 9, 0)).isNull();
    }

    @Test
    void twoNotAtHomeOrUnsafeReportsInNinetyDaysAreEnough() {
        assertThat(CustomerRatingRules.flagReason(new BigDecimal("4.5"), 2, 1)).isNull();
        assertThat(CustomerRatingRules.flagReason(new BigDecimal("4.5"), 2, 2)).contains("2 \"not at home\" or \"unsafe\"");
        assertThat(CustomerRatingRules.flagReason(null, 0, 0)).isNull();
    }
}
