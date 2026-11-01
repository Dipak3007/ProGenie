package com.progenie.booking;

import com.progenie.booking.CartDtos.CartRequest;
import com.progenie.booking.CartDtos.CartResponse;
import com.progenie.shared.security.CurrentUser;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** The customer's cart (Phase 3.5E): one saved checkout, re-checked on every read. */
@RestController
public class CartController {

    private final Carts carts;

    public CartController(Carts carts) {
        this.carts = carts;
    }

    @GetMapping("/api/v1/cart")
    public CartResponse get() {
        return carts.get(CurrentUser.id());
    }

    @PutMapping("/api/v1/cart")
    public CartResponse save(@Valid @RequestBody CartRequest req) {
        return carts.save(CurrentUser.id(), req);
    }

    @DeleteMapping("/api/v1/cart")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void clear() {
        carts.clear(CurrentUser.id());
    }
}
