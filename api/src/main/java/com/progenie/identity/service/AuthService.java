package com.progenie.identity.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import com.progenie.identity.PasswordChanged;
import com.progenie.identity.UserRegisteredEvent;
import com.progenie.identity.domain.RefreshToken;
import com.progenie.identity.domain.Role;
import com.progenie.identity.domain.User;
import com.progenie.identity.repository.RefreshTokenRepository;
import com.progenie.identity.repository.UserRepository;
import com.progenie.identity.service.OtpService.Verified;
import com.progenie.identity.web.dto.AuthDtos.AuthResponse;
import com.progenie.identity.web.dto.AuthDtos.LoginRequest;
import com.progenie.identity.web.dto.AuthDtos.OtpVerifyResponse;
import com.progenie.identity.web.dto.AuthDtos.RegisterRequest;
import com.progenie.identity.web.dto.AuthDtos.UserDto;
import com.progenie.legal.ConsentService;
import com.progenie.notification.delivery.MessageCategory;
import com.progenie.notification.delivery.NotificationPreferences;
import com.progenie.notification.delivery.NotificationPreferences.PreferenceDto;
import com.progenie.shared.config.AppProperties;
import com.progenie.shared.error.ApiException;
import com.progenie.shared.security.JwtTokenService;
import com.progenie.shared.util.Phones;
import com.progenie.shared.util.Times;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * Registration (with consent), password login (with lockout), one-time-code login, password reset and
 * refresh-token rotation.
 */
@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);
    private static final SecureRandom RANDOM = new SecureRandom();

    /** Result of a successful auth call: the JSON body plus the raw refresh token for the cookie. */
    public record AuthResult(AuthResponse response, String refreshToken) {
    }

    /** Result of a correct one-time code; {@code refreshToken} is set only for LOGIN. */
    public record OtpResult(OtpVerifyResponse response, String refreshToken) {
    }

    /** Who is calling, for consent records. */
    public record ClientInfo(String ip, String userAgent) {
    }

    private final UserRepository users;
    private final RefreshTokenRepository refreshTokens;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenService jwtTokenService;
    private final ApplicationEventPublisher events;
    private final AppProperties props;
    private final AuthProperties auth;
    private final OtpService otps;
    private final LoginGuard loginGuard;
    private final UserViews views;
    private final ConsentService consents;
    private final NotificationPreferences preferences;
    private final JdbcClient jdbc;
    private final Clock clock;

    public AuthService(UserRepository users, RefreshTokenRepository refreshTokens, PasswordEncoder passwordEncoder,
                       JwtTokenService jwtTokenService, ApplicationEventPublisher events, AppProperties props,
                       AuthProperties auth, OtpService otps, LoginGuard loginGuard, UserViews views,
                       ConsentService consents, NotificationPreferences preferences, JdbcClient jdbc, Clock clock) {
        this.users = users;
        this.refreshTokens = refreshTokens;
        this.passwordEncoder = passwordEncoder;
        this.jwtTokenService = jwtTokenService;
        this.events = events;
        this.props = props;
        this.auth = auth;
        this.otps = otps;
        this.loginGuard = loginGuard;
        this.views = views;
        this.consents = consents;
        this.preferences = preferences;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /**
     * Creates the account, records acceptance of the current Terms and Privacy Policy (and, for a Genie, the
     * Partner Agreement) and logs the user in. The phone is verified right after, with a VERIFY_PHONE code.
     */
    @Transactional
    public AuthResult register(RegisterRequest req, ClientInfo client) {
        String email = normaliseEmail(req.email());
        if (email != null && users.existsByEmail(email)) {
            throw ApiException.conflict("EMAIL_TAKEN", "An account with this email already exists");
        }
        if (users.existsByPhone(req.phone())) {
            throw ApiException.conflict("PHONE_TAKEN", "An account with this mobile number already exists");
        }
        Role role = req.role() == null ? Role.CUSTOMER : Role.valueOf(req.role());
        if (role == Role.GENIE && !Boolean.TRUE.equals(req.acceptGenieAgreement())) {
            throw ApiException.badRequest("GENIE_AGREEMENT_REQUIRED", "Please accept the Genie Partner Agreement");
        }
        // saveAndFlush: the row must exist before listeners (e.g. provider) insert rows that reference it
        User user = users.saveAndFlush(new User(req.fullName().trim(), email, req.phone(),
            passwordEncoder.encode(req.password()), role));
        events.publishEvent(new UserRegisteredEvent(user.getId(), role, user.getFullName()));
        consents.acceptCurrent(user.getId(), role.name(), client.ip(), client.userAgent());
        if (Boolean.TRUE.equals(req.marketingOptIn())) {
            preferences.update(user.getId(), List.of(new PreferenceDto(MessageCategory.PROMOTIONS, false, true, true)));
        }
        log.info("Registered {} {}", role, user.getId());
        return issueTokens(user, UUID.randomUUID(), client.userAgent());
    }

    /**
     * Password login. 10 wrong passwords within 15 minutes lock password login for 15 minutes
     * (429 LOGIN_LOCKED); logging in with a one-time code keeps working.
     */
    @Transactional
    public AuthResult login(LoginRequest req, String userAgent) {
        String identifier = req.identifier().trim();
        String phone = Phones.national(identifier);
        User user = (identifier.contains("@")
                ? users.findByEmail(identifier.toLowerCase(Locale.ROOT))
                : users.findByPhone(phone == null ? identifier : phone))
            .orElse(null);
        Instant now = clock.instant();
        if (user != null && user.isPasswordLocked(now)) {
            throw locked(user.getPasswordLockedUntil(), now);
        }
        if (user == null || !passwordEncoder.matches(req.password(), user.getPasswordHash())) {
            Instant lockedUntil = user == null || !user.isActive() ? null : loginGuard.recordFailure(user.getId());
            if (lockedUntil != null) {
                throw locked(lockedUntil, now);
            }
            throw ApiException.unauthorized("INVALID_CREDENTIALS", "Wrong email/mobile or password");
        }
        if (!user.isActive()) {
            throw ApiException.unauthorized("ACCOUNT_DISABLED", "This account is not active");
        }
        loginGuard.clear(user.getId());
        return issueTokens(user, UUID.randomUUID(), userAgent);
    }

    private static ApiException locked(Instant until, Instant now) {
        long seconds = Math.max(1, Duration.between(now, until).toSeconds());
        long minutes = (seconds + 59) / 60;
        return ApiException.tooManyRequests("LOGIN_LOCKED", "Too many wrong passwords. Try again in " + minutes
                + " minute" + (minutes == 1 ? "" : "s") + ", or log in with a one-time code")
            .with("retryAfterSeconds", seconds);
    }

    /**
     * A correct one-time code: logs in, hands out a reset token, or confirms a phone/email.
     * noRollbackFor: a wrong code must still count against the challenge's attempts.
     */
    @Transactional(noRollbackFor = ApiException.class)
    public OtpResult verifyOtp(UUID challengeId, String code, String userAgent) {
        Verified v = otps.verify(challengeId, code);
        User user = users.findById(v.userId()).orElseThrow();
        return switch (v.purpose()) {
            case LOGIN -> {
                AuthResult a = issueTokens(user, UUID.randomUUID(), userAgent);
                yield new OtpResult(new OtpVerifyResponse(v.purpose().name(), a.response().accessToken(),
                    a.response().expiresIn(), a.response().user(), null, null), a.refreshToken());
            }
            case RESET_PASSWORD -> {
                String token = newOpaqueToken();
                jdbc.sql("DELETE FROM password_reset_tokens WHERE user_id = :u").param("u", user.getId()).update();
                jdbc.sql("INSERT INTO password_reset_tokens (token_hash, user_id, expires_at) VALUES (:h, :u, :exp)")
                    .param("h", sha256(token)).param("u", user.getId())
                    .param("exp", Times.odt(clock.instant().plus(auth.resetTokenTtl())))
                    .update();
                yield new OtpResult(new OtpVerifyResponse(v.purpose().name(), null, null, null, token,
                    auth.resetTokenTtl().toSeconds()), null);
            }
            case VERIFY_PHONE, VERIFY_EMAIL -> new OtpResult(new OtpVerifyResponse(v.purpose().name(), null, null,
                views.of(user), null, null), null);
        };
    }

    /** Sets a new password with a reset token and signs the user out on every device. */
    @Transactional
    public void resetPassword(String resetToken, String newPassword) {
        record Token(UUID userId, OffsetDateTime expiresAt, OffsetDateTime usedAt) {
        }
        Instant now = clock.instant();
        String hash = sha256(resetToken);
        Token t = jdbc.sql("SELECT user_id, expires_at, used_at FROM password_reset_tokens WHERE token_hash = :h FOR UPDATE")
            .param("h", hash).query(Token.class).optional()
            .filter(x -> x.usedAt() == null && x.expiresAt().toInstant().isAfter(now))
            .orElseThrow(() -> ApiException.badRequest("RESET_TOKEN_INVALID",
                "This reset link has expired. Please ask for a new code"));
        jdbc.sql("UPDATE password_reset_tokens SET used_at = :now WHERE token_hash = :h")
            .param("now", Times.odt(now)).param("h", hash).update();
        User user = users.findById(t.userId())
            .filter(User::isActive)
            .orElseThrow(() -> ApiException.unauthorized("ACCOUNT_DISABLED", "This account is not active"));
        user.changePasswordHash(passwordEncoder.encode(newPassword));
        user.unlockPassword();
        refreshTokens.revokeAllForUser(user.getId(), null, now);
        loginGuard.clear(user.getId());
        events.publishEvent(new PasswordChanged(user.getId(), now, true));
        log.info("Password reset for user {}", user.getId());
    }

    /** Rotates the refresh token. Re-use of an old token revokes the whole login family. */
    @Transactional(noRollbackFor = ApiException.class)
    public AuthResult refresh(String rawToken, String userAgent) {
        if (!StringUtils.hasText(rawToken)) {
            throw ApiException.unauthorized("NO_REFRESH_TOKEN", "Session expired, please log in again");
        }
        Instant now = Instant.now();
        RefreshToken token = refreshTokens.findByTokenHash(sha256(rawToken))
            .orElseThrow(() -> ApiException.unauthorized("INVALID_REFRESH_TOKEN", "Session expired, please log in again"));
        if (token.wasAlreadyUsedOrRevoked()) {
            refreshTokens.revokeFamily(token.getFamilyId(), now);
            log.warn("Refresh token reuse detected for user {}; family revoked", token.getUserId());
            throw ApiException.unauthorized("REFRESH_TOKEN_REUSED", "Session expired, please log in again");
        }
        if (!token.isUsable(now)) {
            throw ApiException.unauthorized("REFRESH_TOKEN_EXPIRED", "Session expired, please log in again");
        }
        token.markUsed(now);
        User user = users.findById(token.getUserId())
            .filter(User::isActive)
            .orElseThrow(() -> ApiException.unauthorized("ACCOUNT_DISABLED", "This account is not active"));
        return issueTokens(user, token.getFamilyId(), userAgent);
    }

    @Transactional
    public void logout(String rawToken) {
        if (StringUtils.hasText(rawToken)) {
            refreshTokens.findByTokenHash(sha256(rawToken))
                .ifPresent(t -> refreshTokens.revokeFamily(t.getFamilyId(), Instant.now()));
        }
    }

    @Transactional(readOnly = true)
    public UserDto me(UUID userId) {
        return users.findById(userId).map(views::of)
            .orElseThrow(() -> ApiException.notFound("USER_NOT_FOUND", "User not found"));
    }

    private AuthResult issueTokens(User user, UUID familyId, String userAgent) {
        String accessToken = jwtTokenService.issueAccessToken(user.getId(), user.getRole().name(), user.getFullName());
        String rawRefresh = newOpaqueToken();
        Instant expiresAt = Instant.now().plus(props.jwt().refreshTokenTtl());
        refreshTokens.save(new RefreshToken(user.getId(), sha256(rawRefresh), familyId, expiresAt, truncate(userAgent)));
        AuthResponse body = new AuthResponse(accessToken, jwtTokenService.accessTokenTtl().toSeconds(), views.of(user));
        return new AuthResult(body, rawRefresh);
    }

    private static String normaliseEmail(String email) {
        return StringUtils.hasText(email) ? email.trim().toLowerCase(Locale.ROOT) : null;
    }

    private static String newOpaqueToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    static String sha256(String value) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String truncate(String value) {
        return value == null || value.length() <= 300 ? value : value.substring(0, 300);
    }
}
