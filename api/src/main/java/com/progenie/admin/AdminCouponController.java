package com.progenie.admin;

import java.util.List;

import com.progenie.promotion.CouponAdminService;
import com.progenie.promotion.CouponAdminService.AdminCouponDto;
import com.progenie.promotion.CouponAdminService.CouponRequest;
import com.progenie.promotion.CouponAdminService.RedemptionDto;
import com.progenie.shared.security.CurrentUser;
import com.progenie.shared.web.PageResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Coupons (role ADMIN): create, edit, pause or resume, and see each coupon's uses. */
@RestController
@RequestMapping("/api/v1/admin/coupons")
public class AdminCouponController {

    public record StatusRequest(boolean active) {
    }

    private final CouponAdminService coupons;

    public AdminCouponController(CouponAdminService coupons) {
        this.coupons = coupons;
    }

    /** status = ACTIVE | PAUSED (optional); q matches code or title. */
    @GetMapping
    public List<AdminCouponDto> list(@RequestParam(required = false) String status, @RequestParam(required = false) String q) {
        return coupons.list(status, q);
    }

    @GetMapping("/{id}")
    public AdminCouponDto get(@PathVariable long id) {
        return coupons.get(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public AdminCouponDto create(@Valid @RequestBody CouponRequest req) {
        return coupons.create(req, CurrentUser.id());
    }

    @PutMapping("/{id}")
    public AdminCouponDto update(@PathVariable long id, @Valid @RequestBody CouponRequest req) {
        return coupons.update(id, req, CurrentUser.id());
    }

    @PutMapping("/{id}/status")
    public AdminCouponDto status(@PathVariable long id, @RequestBody StatusRequest req) {
        return coupons.setActive(id, req.active(), CurrentUser.id());
    }

    @GetMapping("/{id}/redemptions")
    public PageResponse<RedemptionDto> redemptions(@PathVariable long id, @RequestParam(defaultValue = "0") int page,
                                                   @RequestParam(defaultValue = "20") int size) {
        return coupons.redemptions(id, page, size);
    }
}
