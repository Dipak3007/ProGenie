package com.progenie.identity.web;

import com.progenie.identity.service.AccountService;
import com.progenie.identity.web.dto.AuthDtos.UserDto;
import com.progenie.shared.security.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The logged-in user's own account (any role).
 * Password change lives under /api/v1/auth so the browser sends the refresh cookie (its path is /api/v1/auth),
 * which lets us keep the current login while signing out other devices.
 */
@RestController
public class AccountController {

    public record UpdateProfileRequest(@NotBlank @Size(max = 100) String fullName, @Email @Size(max = 150) String email) {
    }

    public record ChangePasswordRequest(@NotBlank String currentPassword,
                                        @NotBlank @Size(min = 8, max = 72, message = "must be 8 to 72 characters") String newPassword) {
    }

    private final AccountService accounts;

    public AccountController(AccountService accounts) {
        this.accounts = accounts;
    }

    @PatchMapping("/api/v1/me")
    public UserDto update(@Valid @RequestBody UpdateProfileRequest req) {
        return accounts.updateProfile(CurrentUser.id(), req.fullName(), req.email());
    }

    @PostMapping("/api/v1/auth/change-password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void changePassword(@Valid @RequestBody ChangePasswordRequest req,
                               @CookieValue(name = AuthController.REFRESH_COOKIE, required = false) String refreshToken) {
        accounts.changePassword(CurrentUser.id(), req.currentPassword(), req.newPassword(), refreshToken);
    }
}
