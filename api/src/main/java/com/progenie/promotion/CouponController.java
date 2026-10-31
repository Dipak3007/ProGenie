package com.progenie.promotion;

import java.util.List;
import java.util.UUID;

import com.progenie.promotion.CouponService.CouponOfferDto;
import com.progenie.shared.security.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.jspecify.annotations.Nullable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Coupons at checkout (role CUSTOMER): the public ones for an order, and checking a typed code. */
@RestController
@RequestMapping("/api/v1/coupons")
public class CouponController {

    /** {@code genieId} is left out for Genie Now (the Genie is not known yet: the service's base price is used). */
    public record CheckRequest(@NotBlank @Size(max = 30) String code, @Nullable UUID genieId, @NotNull Long serviceId,
                               @NotNull UUID addressId) {
    }

    private final CouponService coupons;

    public CouponController(CouponService coupons) {
        this.coupons = coupons;
    }

    /** Public coupons for this order, with the discount each gives (or why it can't be used). */
    @GetMapping
    public List<CouponOfferDto> available(@RequestParam(required = false) @Nullable UUID genieId, @RequestParam long serviceId,
                                          @RequestParam UUID addressId) {
        return coupons.available(CurrentUser.id(), genieId, serviceId, addressId);
    }

    /** A typed code: 200 with the discount, 404 COUPON_NOT_FOUND, or 422 with why it doesn't apply. */
    @PostMapping("/check")
    public CouponOfferDto check(@Valid @RequestBody CheckRequest req) {
        return coupons.check(CurrentUser.id(), req.code(), req.genieId(), req.serviceId(), req.addressId());
    }
}
