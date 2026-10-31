/**
 * Promotion module (Phase 3B): coupons. Admins create percentage or flat coupons with limits and a scope; customers
 * see public ones at checkout or type a code; a booking applies one, and the use is given back if the booking ends
 * before the job starts. ProGenie pays for every discount (ledger account {@code PLATFORM_PROMOTIONS}): the Genie's
 * earnings and the commission never change. Depends on no business module; the booking module calls
 * {@link com.progenie.promotion.CouponService}.
 */
package com.progenie.promotion;
