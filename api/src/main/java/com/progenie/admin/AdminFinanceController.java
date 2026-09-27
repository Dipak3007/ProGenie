package com.progenie.admin;

import java.util.Map;
import java.util.UUID;

import com.progenie.payment.PaymentDtos.GeneratePayoutsRequest;
import com.progenie.payment.PaymentDtos.MarkFailedRequest;
import com.progenie.payment.PaymentDtos.MarkPaidRequest;
import com.progenie.payment.PaymentDtos.PayoutDto;
import com.progenie.payment.PayoutService;
import com.progenie.shared.web.PageResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Weekly Genie payouts (role ADMIN). Payouts are also generated automatically every Monday. */
@RestController
@RequestMapping("/api/v1/admin/payouts")
public class AdminFinanceController {

    private final PayoutService payouts;

    public AdminFinanceController(PayoutService payouts) {
        this.payouts = payouts;
    }

    @GetMapping
    public PageResponse<PayoutDto> list(@RequestParam(required = false) String status,
                                        @RequestParam(defaultValue = "0") int page,
                                        @RequestParam(defaultValue = "20") int size) {
        return payouts.list(status, page, size);
    }

    /** Body is optional: {"periodEnd": "2026-09-27"} (a Sunday). Defaults to last week. */
    @PostMapping("/generate")
    public Map<String, Integer> generate(@RequestBody(required = false) GeneratePayoutsRequest req) {
        return Map.of("created", payouts.generate(req == null ? null : req.periodEnd()));
    }

    @PostMapping("/{id}/mark-paid")
    public PayoutDto markPaid(@PathVariable UUID id, @Valid @RequestBody MarkPaidRequest req) {
        return payouts.markPaid(id, req.reference());
    }

    @PostMapping("/{id}/mark-failed")
    public PayoutDto markFailed(@PathVariable UUID id, @Valid @RequestBody MarkFailedRequest req) {
        return payouts.markFailed(id, req.reason());
    }
}
