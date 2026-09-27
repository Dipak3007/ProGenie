package com.progenie.admin;

import java.util.List;
import java.util.UUID;

import com.progenie.payment.PaymentDtos.PaymentDto;
import com.progenie.payment.PaymentService;
import com.progenie.payment.ReceiptService;
import com.progenie.payment.ReceiptService.ReceiptDto;
import com.progenie.payment.RefundService;
import com.progenie.payment.RefundService.RefundDto;
import com.progenie.payment.RefundService.RefundRequest;
import com.progenie.shared.security.CurrentUser;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Payments, refunds and receipts of a booking; issuing refunds. */
@RestController
@RequestMapping("/api/v1/admin")
public class AdminPaymentsController {

    private final PaymentService payments;
    private final RefundService refunds;
    private final ReceiptService receipts;

    public AdminPaymentsController(PaymentService payments, RefundService refunds, ReceiptService receipts) {
        this.payments = payments;
        this.refunds = refunds;
        this.receipts = receipts;
    }

    @GetMapping("/bookings/{id}/payments")
    public List<PaymentDto> payments(@PathVariable UUID id) {
        return payments.forBookingAdmin(id);
    }

    @GetMapping("/bookings/{id}/refunds")
    public List<RefundDto> refunds(@PathVariable UUID id) {
        return refunds.forBooking(id);
    }

    @GetMapping("/bookings/{id}/receipts")
    public List<ReceiptDto> receipts(@PathVariable UUID id) {
        return receipts.forBooking(id, null);
    }

    /** Refunds (part of) a payment; see RefundRequest for the liability split and method. */
    @PostMapping("/payments/{id}/refunds")
    @ResponseStatus(HttpStatus.CREATED)
    public RefundDto refund(@PathVariable UUID id, @RequestBody RefundRequest req) {
        return refunds.create(CurrentUser.id(), id, req, null);
    }
}
