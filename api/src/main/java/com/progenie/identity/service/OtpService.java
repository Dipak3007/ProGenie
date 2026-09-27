package com.progenie.identity.service;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import com.progenie.identity.OtpRequested;
import com.progenie.identity.domain.User;
import com.progenie.identity.repository.UserRepository;
import com.progenie.shared.error.ApiException;
import com.progenie.shared.ratelimit.RateLimiter;
import com.progenie.shared.util.Masking;
import com.progenie.shared.util.Phones;
import com.progenie.shared.util.Times;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * Six-digit one-time codes for login, password reset and phone/email verification (design doc 16.2).
 * Codes are stored only as HMAC-SHA256(pepper, challengeId:code). A request always creates a challenge,
 * even for an unknown number, so the response never reveals whether an account exists.
 */
@Service
public class OtpService {

    private static final Logger log = LoggerFactory.getLogger(OtpService.class);
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
    static final String DEV_PEPPER = "local-dev-otp-pepper-change-me";

    public enum Purpose { LOGIN, RESET_PASSWORD, VERIFY_PHONE, VERIFY_EMAIL }

    /** What the client needs to show the "enter the code" screen. {@code destination} is masked. */
    public record ChallengeDto(UUID challengeId, String channel, String destination, long expiresInSeconds,
                               long resendAfterSeconds) {
    }

    /** A code that matched. */
    public record Verified(UUID userId, Purpose purpose, String channel, String destination) {
    }

    record ChallengeRow(UUID id, UUID userId, String purpose, String channel, String destination, String codeHash,
                        OffsetDateTime expiresAt, int attempts, OffsetDateTime consumedAt) {
    }

    private final JdbcClient jdbc;
    private final UserRepository users;
    private final RateLimiter rateLimiter;
    private final ApplicationEventPublisher events;
    private final AuthProperties props;
    private final Clock clock;

    public OtpService(JdbcClient jdbc, UserRepository users, RateLimiter rateLimiter, ApplicationEventPublisher events,
                      AuthProperties props, Clock clock) {
        this.jdbc = jdbc;
        this.users = users;
        this.rateLimiter = rateLimiter;
        this.events = events;
        this.props = props;
        this.clock = clock;
    }

    @PostConstruct
    void warnAboutDevPepper() {
        if (DEV_PEPPER.equals(props.otpPepper())) {
            log.warn("PROGENIE_OTP_PEPPER is not set: using the development pepper. Set a long random secret in production.");
        }
    }

    /**
     * Issues a code. For VERIFY_PHONE / VERIFY_EMAIL the caller must be logged in and the identifier (optional)
     * must be their own current phone or email.
     */
    @Transactional
    public ChallengeDto request(String identifier, Purpose purpose, UUID currentUserId, String ip) {
        User owner = null;
        String destination;
        String channel;
        if (purpose == Purpose.VERIFY_PHONE || purpose == Purpose.VERIFY_EMAIL) {
            if (currentUserId == null) {
                throw ApiException.unauthorized("NOT_AUTHENTICATED", "Please log in");
            }
            owner = users.findById(currentUserId)
                .orElseThrow(() -> ApiException.unauthorized("NOT_AUTHENTICATED", "Please log in"));
            boolean phone = purpose == Purpose.VERIFY_PHONE;
            destination = phone ? owner.getPhone() : owner.getEmail();
            if (destination == null) {
                throw ApiException.unprocessable("NO_EMAIL", "Add an email address to your profile first");
            }
            if (StringUtils.hasText(identifier) && !destination.equalsIgnoreCase(normalise(identifier))) {
                throw ApiException.badRequest("NOT_YOUR_IDENTIFIER", "You can only verify your own "
                    + (phone ? "mobile number" : "email address"));
            }
            if (phone ? owner.isPhoneVerified() : owner.isEmailVerified()) {
                throw ApiException.conflict("ALREADY_VERIFIED", "This is already verified");
            }
            channel = phone ? "SMS" : "EMAIL";
        } else {
            destination = normalise(identifier);
            channel = destination.contains("@") ? "EMAIL" : "SMS";
            owner = (channel.equals("EMAIL") ? users.findByEmail(destination) : users.findByPhone(destination))
                .filter(User::isActive)
                .orElse(null);
        }

        enforceLimits(destination, ip);

        Instant now = clock.instant();
        UUID challengeId = UUID.randomUUID();
        String code = "%06d".formatted(RANDOM.nextInt(1_000_000));
        // an older code for the same destination and purpose stops working
        jdbc.sql("""
                UPDATE otp_challenges SET expires_at = :now
                 WHERE destination = :dest AND purpose = :purpose AND consumed_at IS NULL AND expires_at > :now
                """)
            .param("now", Times.odt(now)).param("dest", destination).param("purpose", purpose.name())
            .update();
        jdbc.sql("""
                INSERT INTO otp_challenges (id, user_id, purpose, channel, destination, code_hash, expires_at, ip)
                VALUES (:id, :user, :purpose, :channel, :dest, :hash, :expires, :ip)
                """)
            .param("id", challengeId)
            .param("user", owner == null ? null : owner.getId())
            .param("purpose", purpose.name())
            .param("channel", channel)
            .param("dest", destination)
            .param("hash", hmac(challengeId, code))
            .param("expires", Times.odt(now.plus(props.otpTtl())))
            .param("ip", ip == null ? null : ip.length() > 45 ? ip.substring(0, 45) : ip)
            .update();
        if (owner != null) {
            events.publishEvent(new OtpRequested(challengeId, owner.getId(), owner.getFullName(), channel, destination,
                purpose.name(), code, props.otpTtl().toMinutes()));
            log.info("OTP {} issued to {} ({})", purpose, Masking.destination(destination), owner.getId());
        } else {
            log.info("OTP {} requested for unknown {}", purpose, Masking.destination(destination));
        }
        return new ChallengeDto(challengeId, channel, Masking.destination(destination), props.otpTtl().toSeconds(),
            props.otpResendAfter().toSeconds());
    }

    /** Checks a code. Wrong codes count even though the call fails (hence noRollbackFor). */
    @Transactional(noRollbackFor = ApiException.class)
    public Verified verify(UUID challengeId, String code) {
        Instant now = clock.instant();
        ChallengeRow c = jdbc.sql("""
                SELECT id, user_id, purpose, channel, destination, code_hash, expires_at, attempts, consumed_at
                  FROM otp_challenges WHERE id = :id FOR UPDATE
                """)
            .param("id", challengeId).query(ChallengeRow.class).optional()
            .orElseThrow(() -> ApiException.badRequest("OTP_INVALID", "The code is wrong"));
        if (c.consumedAt() != null || !c.expiresAt().toInstant().isAfter(now)) {
            throw ApiException.badRequest("OTP_EXPIRED", "This code has expired. Please request a new one");
        }
        if (c.attempts() >= props.otpMaxAttempts()) {
            throw ApiException.badRequest("OTP_EXPIRED", "Too many wrong codes. Please request a new one");
        }
        boolean matches = code != null && code.matches("[0-9]{6}")
            && MessageDigest.isEqual(hmac(challengeId, code).getBytes(StandardCharsets.US_ASCII),
                c.codeHash().getBytes(StandardCharsets.US_ASCII));
        if (!matches || c.userId() == null) {
            int attempts = c.attempts() + 1;
            jdbc.sql("UPDATE otp_challenges SET attempts = :a WHERE id = :id").param("a", attempts).param("id", c.id()).update();
            int left = Math.max(0, props.otpMaxAttempts() - attempts);
            throw ApiException.badRequest(left == 0 ? "OTP_EXPIRED" : "OTP_INVALID",
                    left == 0 ? "Too many wrong codes. Please request a new one" : "The code is wrong")
                .with("attemptsLeft", left);
        }
        jdbc.sql("UPDATE otp_challenges SET consumed_at = :now WHERE id = :id")
            .param("now", Times.odt(now)).param("id", c.id()).update();

        User user = users.findById(c.userId())
            .filter(User::isActive)
            .orElseThrow(() -> ApiException.unauthorized("ACCOUNT_DISABLED", "This account is not active"));
        // A matching code proves the person holds this phone / inbox.
        if ("SMS".equals(c.channel()) && c.destination().equals(user.getPhone())) {
            user.markPhoneVerified(now);
        } else if ("EMAIL".equals(c.channel()) && c.destination().equalsIgnoreCase(user.getEmail())) {
            user.markEmailVerified(now);
        }
        return new Verified(user.getId(), Purpose.valueOf(c.purpose()), c.channel(), c.destination());
    }

    /** Deletes a user's codes (account deletion). */
    @Transactional
    public void deleteAllFor(UUID userId) {
        jdbc.sql("DELETE FROM otp_challenges WHERE user_id = :u").param("u", userId).update();
    }

    private void enforceLimits(String destination, String ip) {
        Optional<OffsetDateTime> last = jdbc.sql("""
                SELECT created_at FROM otp_challenges WHERE destination = :d ORDER BY created_at DESC LIMIT 1
                """)
            .param("d", destination).query(OffsetDateTime.class).optional();
        Instant now = clock.instant();
        if (last.isPresent()) {
            Duration since = Duration.between(last.get().toInstant(), now);
            if (since.compareTo(props.otpResendAfter()) < 0) {
                long wait = props.otpResendAfter().minus(since).toSeconds() + 1;
                throw ApiException.tooManyRequests("OTP_TOO_SOON", "Please wait " + wait + " seconds before asking for a new code")
                    .with("retryAfterSeconds", wait);
            }
        }
        if (ip != null && !rateLimiter.tryAcquire("otp:ip:" + ip, props.otpPerIpPerHour(), Duration.ofHours(1))) {
            throw ApiException.tooManyRequests("OTP_RATE_LIMITED", "Too many codes requested. Please try again later")
                .with("retryAfterSeconds", 3600);
        }
        if (!rateLimiter.tryAcquire("otp:dest:" + destination, props.otpPerDestinationPerHour(), Duration.ofHours(1))) {
            throw ApiException.tooManyRequests("OTP_RATE_LIMITED", "Too many codes sent to this number or email. Please try again later")
                .with("retryAfterSeconds", 3600);
        }
    }

    /** Email → trimmed lower case; phone → 10-digit national number (how phones are stored). */
    static String normalise(String identifier) {
        if (!StringUtils.hasText(identifier)) {
            throw ApiException.badRequest("BAD_IDENTIFIER", "Enter your mobile number or email address");
        }
        String value = identifier.trim();
        if (value.contains("@")) {
            if (!EMAIL.matcher(value).matches() || value.length() > 150) {
                throw ApiException.badRequest("BAD_IDENTIFIER", "Enter a valid email address");
            }
            return value.toLowerCase(Locale.ROOT);
        }
        String national = Phones.national(value);
        if (national == null) {
            throw ApiException.badRequest("BAD_IDENTIFIER", "Enter a valid 10-digit mobile number");
        }
        return national;
    }

    String hmac(UUID challengeId, String code) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(props.otpPepper().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal((challengeId + ":" + code).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException(e);
        }
    }
}
