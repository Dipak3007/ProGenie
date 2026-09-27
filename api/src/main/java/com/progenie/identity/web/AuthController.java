package com.progenie.identity.web;

import java.time.Duration;
import java.util.UUID;

import com.progenie.identity.service.AuthService;
import com.progenie.identity.service.AuthService.AuthResult;
import com.progenie.identity.service.AuthService.ClientInfo;
import com.progenie.identity.service.AuthService.OtpResult;
import com.progenie.identity.service.OtpService;
import com.progenie.identity.service.OtpService.ChallengeDto;
import com.progenie.identity.web.dto.AuthDtos.AuthResponse;
import com.progenie.identity.web.dto.AuthDtos.LoginRequest;
import com.progenie.identity.web.dto.AuthDtos.OtpRequest;
import com.progenie.identity.web.dto.AuthDtos.OtpVerifyRequest;
import com.progenie.identity.web.dto.AuthDtos.OtpVerifyResponse;
import com.progenie.identity.web.dto.AuthDtos.RegisterRequest;
import com.progenie.identity.web.dto.AuthDtos.ResetPasswordRequest;
import com.progenie.identity.web.dto.AuthDtos.UserDto;
import com.progenie.shared.config.AppProperties;
import com.progenie.shared.security.CurrentUser;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Auth endpoints. The access token goes in the JSON body (Angular keeps it in memory);
 * the refresh token goes in an HttpOnly cookie that JavaScript can never read.
 */
@RestController
public class AuthController {

    static final String REFRESH_COOKIE = "pg_refresh";
    private static final String COOKIE_PATH = "/api/v1/auth";

    private final AuthService authService;
    private final OtpService otpService;
    private final AppProperties props;

    public AuthController(AuthService authService, OtpService otpService, AppProperties props) {
        this.authService = authService;
        this.otpService = otpService;
        this.props = props;
    }

    @PostMapping("/api/v1/auth/register")
    public ResponseEntity<AuthResponse> register(@Valid @RequestBody RegisterRequest request, HttpServletRequest http,
                                                 @RequestHeader(value = HttpHeaders.USER_AGENT, required = false) String userAgent) {
        return withCookie(HttpStatus.CREATED, authService.register(request, new ClientInfo(http.getRemoteAddr(), userAgent)));
    }

    @PostMapping("/api/v1/auth/login")
    public ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequest request,
                                              @RequestHeader(value = HttpHeaders.USER_AGENT, required = false) String userAgent) {
        return withCookie(HttpStatus.OK, authService.login(request, userAgent));
    }

    /**
     * Sends a 6-digit code. Always 202 with a challenge id (whether or not the account exists);
     * 429 OTP_TOO_SOON / OTP_RATE_LIMITED when asked too often.
     */
    @PostMapping("/api/v1/auth/otp/request")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ChallengeDto requestOtp(@Valid @RequestBody OtpRequest request, HttpServletRequest http) {
        return otpService.request(request.identifier(), OtpService.Purpose.valueOf(request.purpose()),
            CurrentUser.idOrNull(), http.getRemoteAddr());
    }

    @PostMapping("/api/v1/auth/otp/verify")
    public ResponseEntity<OtpVerifyResponse> verifyOtp(@Valid @RequestBody OtpVerifyRequest request,
                                                       @RequestHeader(value = HttpHeaders.USER_AGENT, required = false) String userAgent) {
        OtpResult result = authService.verifyOtp(request.challengeId(), request.code().trim(), userAgent);
        ResponseEntity.BodyBuilder ok = ResponseEntity.ok();
        if (result.refreshToken() != null) {
            ok.header(HttpHeaders.SET_COOKIE, cookie(result.refreshToken(), props.jwt().refreshTokenTtl()).toString());
        }
        return ok.body(result.response());
    }

    @PostMapping("/api/v1/auth/password/reset")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void resetPassword(@Valid @RequestBody ResetPasswordRequest request) {
        authService.resetPassword(request.resetToken(), request.newPassword());
    }

    @PostMapping("/api/v1/auth/refresh")
    public ResponseEntity<AuthResponse> refresh(@CookieValue(name = REFRESH_COOKIE, required = false) String refreshToken,
                                                @RequestHeader(value = HttpHeaders.USER_AGENT, required = false) String userAgent) {
        return withCookie(HttpStatus.OK, authService.refresh(refreshToken, userAgent));
    }

    @PostMapping("/api/v1/auth/logout")
    public ResponseEntity<Void> logout(@CookieValue(name = REFRESH_COOKIE, required = false) String refreshToken) {
        authService.logout(refreshToken);
        return ResponseEntity.noContent()
            .header(HttpHeaders.SET_COOKIE, cookie("", Duration.ZERO).toString())
            .build();
    }

    @GetMapping("/api/v1/me")
    public UserDto me() {
        UUID userId = CurrentUser.id();
        return authService.me(userId);
    }

    private ResponseEntity<AuthResponse> withCookie(HttpStatus status, AuthResult result) {
        ResponseCookie cookie = cookie(result.refreshToken(), props.jwt().refreshTokenTtl());
        return ResponseEntity.status(status)
            .header(HttpHeaders.SET_COOKIE, cookie.toString())
            .body(result.response());
    }

    private ResponseCookie cookie(String value, Duration maxAge) {
        return ResponseCookie.from(REFRESH_COOKIE, value)
            .httpOnly(true)
            .secure(props.cookie().secure())
            .sameSite("Strict")
            .path(COOKIE_PATH)
            .maxAge(maxAge)
            .build();
    }
}
