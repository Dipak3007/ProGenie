package com.progenie.booking;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** How the cart and the booking's PRICE_CHANGED message write amounts (Phase 3.5E). */
class CartTextTest {

    @Test
    void amountsAreWrittenLikeTheWebApp() {
        assertThat(Carts.rupees(new BigDecimal("1234.00"))).isEqualTo("₹1,234");
        assertThat(Carts.rupees(new BigDecimal("123456.5"))).isEqualTo("\u20b9123,456.50");
        assertThat(Carts.rupees(new BigDecimal("0"))).isEqualTo("₹0");
    }
}
