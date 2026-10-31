package com.progenie.promotion;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import com.progenie.shared.config.QueryConstants;
import com.progenie.shared.error.ApiException;
import com.progenie.shared.util.QueryUtil;
import com.progenie.shared.util.Times;
import com.progenie.shared.web.PageResponse;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/** Admin → Coupons: create, edit, pause/resume, and see how often each coupon was used. */
@Service
public class CouponAdminService {

    private static final Logger log = LoggerFactory.getLogger(CouponAdminService.class);

    public enum DiscountType { PERCENT, FLAT }

    public enum Scope { ALL, CATEGORY, SERVICE, CITY }

    /**
     * Create or edit a coupon. The code can't change after creation. {@code maxDiscount} only for PERCENT;
     * the target id matching {@code scope} is required (categoryId, serviceId or cityId). {@code validFrom}
     * defaults to now. {@code listed} (default true): shown at checkout; otherwise the code works only when typed.
     */
    public record CouponRequest(@NotBlank @Pattern(regexp = "^[A-Za-z0-9]{4,20}$", message = "4-20 letters or digits") String code,
                                @NotBlank @Size(max = 80) String title, @Size(max = 300) String description,
                                @NotNull DiscountType discountType, @NotNull @DecimalMin(value = "0.01") BigDecimal discountValue,
                                @DecimalMin(value = "0.01") BigDecimal maxDiscount, @DecimalMin("0") BigDecimal minOrder,
                                OffsetDateTime validFrom, OffsetDateTime validUntil, @Min(1) Integer totalLimit,
                                @Min(1) Integer perCustomerLimit, Boolean firstBookingOnly, @NotNull Scope scope,
                                Long categoryId, Long serviceId, Long cityId, Boolean listed) {
    }

    /** A coupon with its usage: {@code uses} live uses, {@code released} given back, {@code totalDiscount} on completed jobs. */
    public record AdminCouponDto(long id, String code, String title, @Nullable String description, String discountType,
                                 BigDecimal discountValue, @Nullable BigDecimal maxDiscount, BigDecimal minOrder,
                                 OffsetDateTime validFrom, @Nullable OffsetDateTime validUntil, @Nullable Integer totalLimit,
                                 int perCustomerLimit, boolean firstBookingOnly, String scope, @Nullable Long categoryId,
                                 @Nullable String categoryName, @Nullable Long serviceId, @Nullable String serviceName,
                                 @Nullable Long cityId, @Nullable String cityName, boolean listed, String status,
                                 long uses, long released, BigDecimal totalDiscount, OffsetDateTime createdAt,
                                 OffsetDateTime updatedAt) {
    }

    public record RedemptionDto(long id, UUID bookingId, String bookingRef, String customerName, BigDecimal discountAmount,
                                String status, String bookingStatus, OffsetDateTime createdAt,
                                @Nullable OffsetDateTime releasedAt) {
    }

    private final JdbcClient jdbc;
    private final Clock clock;

    public CouponAdminService(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<AdminCouponDto> list(@Nullable String status, @Nullable String q) {
        return jdbc.sql(QueryUtil.get(QueryConstants.GET_COUPON_ADMIN_LIST))
            .param("status", StringUtils.hasText(status) ? status.trim().toUpperCase() : null)
            .param("q", StringUtils.hasText(q) ? "%" + q.trim() + "%" : null)
            .query(AdminCouponDto.class).list();
    }

    @Transactional(readOnly = true)
    public AdminCouponDto get(long id) {
        return jdbc.sql(QueryUtil.get(QueryConstants.GET_COUPON_ADMIN_BY_ID)).param("id", id)
            .query(AdminCouponDto.class).optional()
            .orElseThrow(() -> ApiException.notFound("COUPON_NOT_FOUND", "Coupon not found"));
    }

    @Transactional
    public AdminCouponDto create(CouponRequest req, UUID adminId) {
        String code = CouponRules.normalise(req.code());
        validate(req);
        if (Boolean.TRUE.equals(jdbc.sql(QueryUtil.get(QueryConstants.EXISTS_COUPON_CODE)).param("code", code)
            .query(Boolean.class).single())) {
            throw ApiException.conflict("COUPON_CODE_TAKEN", "A coupon with code " + code + " already exists");
        }
        long id;
        try {
            id = bind(jdbc.sql(QueryUtil.get(QueryConstants.INSERT_COUPON)), req)
                .param("code", code).param("admin", adminId).query(Long.class).single();
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("COUPON_CODE_TAKEN", "A coupon with code " + code + " already exists");
        }
        log.info("Coupon {} created by admin {}", code, adminId);
        return get(id);
    }

    @Transactional
    public AdminCouponDto update(long id, CouponRequest req, UUID adminId) {
        AdminCouponDto current = get(id);
        if (!current.code().equals(CouponRules.normalise(req.code()))) {
            throw ApiException.badRequest("COUPON_CODE_LOCKED", "The code of a coupon can't be changed; create a new coupon");
        }
        validate(req);
        bind(jdbc.sql(QueryUtil.get(QueryConstants.UPDATE_COUPON)), req).param("id", id).update();
        log.info("Coupon {} edited by admin {}", current.code(), adminId);
        return get(id);
    }

    /** Pausing stops new uses at once; bookings that already have the coupon keep their discount. */
    @Transactional
    public AdminCouponDto setActive(long id, boolean active, UUID adminId) {
        AdminCouponDto current = get(id);
        jdbc.sql(QueryUtil.get(QueryConstants.UPDATE_COUPON_STATUS)).param("id", id)
            .param("status", active ? "ACTIVE" : "PAUSED").update();
        log.info("Coupon {} {} by admin {}", current.code(), active ? "resumed" : "paused", adminId);
        return get(id);
    }

    @Transactional(readOnly = true)
    public PageResponse<RedemptionDto> redemptions(long id, int page, int size) {
        get(id);
        long total = jdbc.sql(QueryUtil.get(QueryConstants.COUNT_COUPON_REDEMPTIONS)).param("id", id)
            .query(Long.class).single();
        List<RedemptionDto> items = jdbc.sql(QueryUtil.get(QueryConstants.GET_COUPON_REDEMPTIONS)).param("id", id)
            .param("limit", PageResponse.clampSize(size)).param("offset", PageResponse.offset(page, size))
            .query(RedemptionDto.class).list();
        return new PageResponse<>(items, Math.max(page, 0), PageResponse.clampSize(size), total);
    }

    private void validate(CouponRequest r) {
        if (r.discountType() == DiscountType.PERCENT && r.discountValue().compareTo(BigDecimal.valueOf(100)) > 0) {
            throw invalid("discountValue", "A percentage can be at most 100");
        }
        if (r.discountType() == DiscountType.FLAT && r.maxDiscount() != null) {
            throw invalid("maxDiscount", "Only percentage coupons have a maximum discount");
        }
        OffsetDateTime from = r.validFrom() == null ? Times.odt(clock.instant()) : r.validFrom();
        if (r.validUntil() != null && !r.validUntil().isAfter(from)) {
            throw invalid("validUntil", "The end must be after the start");
        }
        switch (r.scope()) {
            case CATEGORY -> requireTarget(QueryConstants.EXISTS_COUPON_CATEGORY, r.categoryId(), "categoryId", "category");
            case SERVICE -> requireTarget(QueryConstants.EXISTS_COUPON_SERVICE, r.serviceId(), "serviceId", "service");
            case CITY -> requireTarget(QueryConstants.EXISTS_COUPON_CITY, r.cityId(), "cityId", "city");
            case ALL -> {
            }
        }
    }

    private void requireTarget(String existsKey, @Nullable Long id, String field, String what) {
        if (id == null || !Boolean.TRUE.equals(jdbc.sql(QueryUtil.get(existsKey)).param("id", id).query(Boolean.class).single())) {
            throw invalid(field, "Choose the " + what + " this coupon is for");
        }
    }

    private static ApiException invalid(String field, String message) {
        return ApiException.badRequest("INVALID_COUPON", message).with("field", field);
    }

    private JdbcClient.StatementSpec bind(JdbcClient.StatementSpec spec, CouponRequest r) {
        Scope scope = r.scope();
        return spec
            .param("title", r.title().trim())
            .param("description", StringUtils.hasText(r.description()) ? r.description().trim() : null)
            .param("discountType", r.discountType().name())
            .param("discountValue", r.discountValue())
            .param("maxDiscount", r.discountType() == DiscountType.PERCENT ? r.maxDiscount() : null)
            .param("minOrder", r.minOrder() == null ? BigDecimal.ZERO : r.minOrder())
            .param("validFrom", r.validFrom() == null ? Times.odt(clock.instant()) : r.validFrom())
            .param("validUntil", r.validUntil())
            .param("totalLimit", r.totalLimit())
            .param("perCustomerLimit", r.perCustomerLimit() == null ? 1 : r.perCustomerLimit())
            .param("firstBookingOnly", Boolean.TRUE.equals(r.firstBookingOnly()))
            .param("scope", scope.name())
            .param("categoryId", scope == Scope.CATEGORY ? r.categoryId() : null)
            .param("serviceId", scope == Scope.SERVICE ? r.serviceId() : null)
            .param("cityId", scope == Scope.CITY ? r.cityId() : null)
            .param("isPublic", !Boolean.FALSE.equals(r.listed()));
    }
}
