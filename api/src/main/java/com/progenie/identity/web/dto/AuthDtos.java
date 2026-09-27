package com.progenie.identity.web.dto;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Request/response records for the auth endpoints. */
public final class AuthDtos {

    private AuthDtos() {
    }

    public record RegisterRequest(
        @NotBlank @Size(max = 100) String fullName,
        @Email @Size(max = 150) String email,
        @NotBlank @Pattern(regexp = "^[6-9][0-9]{9}$", message = "must be a 10-digit Indian mobile number") String phone,
        @NotBlank @Size(min = 8, max = 72, message = "must be 8 to 72 characters") String password,
        @Pattern(regexp = "CUSTOMER|GENIE", message = "must be CUSTOMER or GENIE") String role,
        @AssertTrue(message = "must accept the Terms of Service and Privacy Policy") boolean acceptTerms,
        Boolean acceptGenieAgreement,
        Boolean marketingOptIn) {
    }

    /** Ask for a one-time code. {@code identifier} is optional for VERIFY_PHONE / VERIFY_EMAIL (the account's own). */
    public record OtpRequest(String identifier,
                             @NotBlank @Pattern(regexp = "LOGIN|RESET_PASSWORD|VERIFY_PHONE|VERIFY_EMAIL") String purpose) {
    }

    public record OtpVerifyRequest(@NotNull UUID challengeId, @NotBlank String code) {
    }

    /**
     * Result of a correct code. LOGIN: the usual access token and user (plus the refresh cookie).
     * RESET_PASSWORD: a single-use reset token. VERIFY_*: the updated user.
     */
    public record OtpVerifyResponse(String purpose, String accessToken, Long expiresIn, UserDto user, String resetToken,
                                    Long resetTokenExpiresIn) {
    }

    public record ResetPasswordRequest(@NotBlank String resetToken,
                                       @NotBlank @Size(min = 8, max = 72, message = "must be 8 to 72 characters") String newPassword) {
    }

    /** {@code identifier} is an email address or a 10-digit mobile number. */
    public record LoginRequest(@NotBlank String identifier, @NotBlank String password) {
    }

    /**
     * The account as the app sees it.
     *
     * @param pendingConsents      policy kinds the user must (re-)accept before booking, e.g. ["TERMS"]
     * @param deletionScheduledFor set while an account deletion is pending (the app offers "Cancel deletion")
     */
    public record UserDto(UUID id, String fullName, String email, String phone, String role, boolean phoneVerified,
                          boolean emailVerified, List<String> pendingConsents, OffsetDateTime deletionScheduledFor) {
    }

    public record AuthResponse(String accessToken, long expiresIn, UserDto user) {
    }
}
