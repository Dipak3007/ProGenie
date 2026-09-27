package com.progenie.payment;

import java.util.List;

import com.progenie.payment.PaymentDtos.LedgerLineDto;
import com.progenie.payment.PaymentDtos.PayoutDto;
import com.progenie.payment.PaymentDtos.WalletDto;
import com.progenie.shared.security.CurrentUser;
import com.progenie.shared.web.PageResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** A Genie's earnings: wallet summary, statement and payouts (role GENIE). */
@RestController
@RequestMapping("/api/v1/genie")
public class GenieWalletController {

    private final WalletService wallets;

    public GenieWalletController(WalletService wallets) {
        this.wallets = wallets;
    }

    @GetMapping("/wallet")
    public WalletDto wallet() {
        return wallets.wallet(CurrentUser.id());
    }

    @GetMapping("/wallet/entries")
    public PageResponse<LedgerLineDto> entries(@RequestParam(defaultValue = "0") int page,
                                               @RequestParam(defaultValue = "20") int size) {
        return wallets.entries(CurrentUser.id(), page, size);
    }

    @GetMapping("/payouts")
    public List<PayoutDto> payouts() {
        return wallets.payouts(CurrentUser.id());
    }
}
