package com.progenie.payment;

import java.util.List;
import java.util.UUID;

import com.progenie.payment.ReceiptService.PdfFile;
import com.progenie.payment.ReceiptService.ReceiptDto;
import com.progenie.shared.security.CurrentUser;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/** Receipts and credit notes: a list per booking (customer) and the PDF (its customer or an admin). */
@RestController
public class ReceiptController {

    private final ReceiptService receipts;

    public ReceiptController(ReceiptService receipts) {
        this.receipts = receipts;
    }

    @GetMapping("/api/v1/bookings/{bookingId}/receipts")
    public List<ReceiptDto> forBooking(@PathVariable UUID bookingId) {
        return receipts.forBooking(bookingId, CurrentUser.id());
    }

    @GetMapping("/api/v1/receipts/{id}/pdf")
    public ResponseEntity<byte[]> pdf(@PathVariable UUID id) {
        PdfFile file = receipts.pdf(id, CurrentUser.id(), "ADMIN".equals(CurrentUser.role()));
        return ResponseEntity.ok()
            .contentType(MediaType.APPLICATION_PDF)
            .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(file.fileName()).build().toString())
            .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
            .body(file.content());
    }
}
