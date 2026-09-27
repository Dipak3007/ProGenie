package com.progenie.admin;

import java.util.UUID;

import com.progenie.payment.PaymentDtos.SettlementRequest;
import com.progenie.payment.PaymentDtos.WalletDto;
import com.progenie.payment.PayoutService;
import com.progenie.payment.WalletService;
import com.progenie.provider.GenieDocumentService;
import com.progenie.provider.GenieProfileController;
import com.progenie.provider.GenieVerificationService;
import com.progenie.provider.GenieVerificationService.AdminGenieDetailDto;
import com.progenie.provider.GenieVerificationService.Decision;
import com.progenie.provider.GenieVerificationService.QueueItemDto;
import com.progenie.shared.security.CurrentUser;
import com.progenie.shared.web.PageResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Genie verification queue, KYC review, reliability flags and Genie finances (role ADMIN). */
@RestController
@RequestMapping("/api/v1/admin")
public class AdminGenieController {

    public record DecisionRequest(@NotNull Decision decision, @Size(max = 500) String note) {
    }

    public record DocumentReviewRequest(boolean approve, @Size(max = 300) String note) {
    }

    private final GenieVerificationService verification;
    private final GenieDocumentService documents;
    private final WalletService wallets;
    private final PayoutService payouts;

    public AdminGenieController(GenieVerificationService verification, GenieDocumentService documents,
                                WalletService wallets, PayoutService payouts) {
        this.verification = verification;
        this.documents = documents;
        this.wallets = wallets;
        this.payouts = payouts;
    }

    /** Example: /api/v1/admin/genies?status=UNDER_REVIEW  or  ?flagged=true */
    @GetMapping("/genies")
    public PageResponse<QueueItemDto> genies(@RequestParam(required = false) String status,
                                             @RequestParam(required = false) Boolean flagged,
                                             @RequestParam(required = false) String q,
                                             @RequestParam(defaultValue = "0") int page,
                                             @RequestParam(defaultValue = "20") int size) {
        return verification.queue(status, flagged, q, page, size);
    }

    @GetMapping("/genies/{id}")
    public AdminGenieDetailDto genie(@PathVariable UUID id) {
        return verification.detail(id);
    }

    /** APPROVE | NEEDS_CHANGES | REJECT | SUSPEND | REINSTATE (a note is required except for APPROVE/REINSTATE). */
    @PostMapping("/genies/{id}/decision")
    public AdminGenieDetailDto decide(@PathVariable UUID id, @Valid @RequestBody DecisionRequest req) {
        return verification.decide(id, CurrentUser.id(), req.decision(), req.note());
    }

    @PostMapping("/genies/{id}/clear-flag")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void clearFlag(@PathVariable UUID id) {
        verification.clearFlag(id, CurrentUser.id());
    }

    @PostMapping("/genie-documents/{docId}/review")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void reviewDocument(@PathVariable UUID docId, @Valid @RequestBody DocumentReviewRequest req) {
        verification.reviewDocument(docId, CurrentUser.id(), req.approve(), req.note());
    }

    @GetMapping("/genie-documents/{docId}/file")
    public ResponseEntity<InputStreamResource> documentFile(@PathVariable UUID docId) {
        return GenieProfileController.fileResponse(documents.find(docId, null), documents);
    }

    @GetMapping("/genies/{id}/wallet")
    public WalletDto wallet(@PathVariable UUID id) {
        return wallets.wallet(id);
    }

    /** Record money a Genie paid to clear commission dues from cash jobs. */
    @PostMapping("/genies/{id}/settlements")
    public WalletDto settlement(@PathVariable UUID id, @Valid @RequestBody SettlementRequest req) {
        payouts.recordSettlement(id, req.amount(), req.reference());
        return wallets.wallet(id);
    }
}
