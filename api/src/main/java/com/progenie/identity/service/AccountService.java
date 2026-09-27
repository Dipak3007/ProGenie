package com.progenie.identity.service;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Locale;
import java.util.UUID;

import com.progenie.identity.domain.RefreshToken;
import com.progenie.identity.domain.User;
import com.progenie.identity.domain.UserStatus;
import com.progenie.identity.repository.RefreshTokenRepository;
import com.progenie.identity.repository.UserRepository;
import com.progenie.identity.web.dto.AuthDtos.UserDto;
import com.progenie.shared.error.ApiException;
import com.progenie.shared.web.PageResponse;
import com.progenie.identity.PasswordChanged;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/** Self-service account changes and admin status changes. */
@Service
public class AccountService {

    private final UserRepository users;
    private final RefreshTokenRepository refreshTokens;
    private final PasswordEncoder passwordEncoder;
    private final JdbcClient jdbc;
    private final UserViews views;
    private final ApplicationEventPublisher events;

    public AccountService(UserRepository users, RefreshTokenRepository refreshTokens, PasswordEncoder passwordEncoder,
                          JdbcClient jdbc, UserViews views, ApplicationEventPublisher events) {
        this.views = views;
        this.events = events;
        this.users = users;
        this.refreshTokens = refreshTokens;
        this.passwordEncoder = passwordEncoder;
        this.jdbc = jdbc;
    }

    @Transactional
    public UserDto updateProfile(UUID userId, String fullName, String email) {
        User user = load(userId);
        String normalisedEmail = StringUtils.hasText(email) ? email.trim().toLowerCase(Locale.ROOT) : null;
        if (normalisedEmail != null && !normalisedEmail.equals(user.getEmail()) && users.existsByEmail(normalisedEmail)) {
            throw ApiException.conflict("EMAIL_TAKEN", "An account with this email already exists");
        }
        user.updateProfile(fullName.trim(), normalisedEmail);
        return views.of(user);
    }

    /** Changes the password and signs out every other device (the current login keeps working). */
    @Transactional
    public void changePassword(UUID userId, String currentPassword, String newPassword, String currentRefreshToken) {
        User user = load(userId);
        if (!passwordEncoder.matches(currentPassword, user.getPasswordHash())) {
            throw ApiException.badRequest("WRONG_PASSWORD", "The current password is not correct");
        }
        user.changePasswordHash(passwordEncoder.encode(newPassword));
        UUID keepFamily = StringUtils.hasText(currentRefreshToken)
            ? refreshTokens.findByTokenHash(AuthService.sha256(currentRefreshToken)).map(RefreshToken::getFamilyId).orElse(null)
            : null;
        refreshTokens.revokeAllForUser(userId, keepFamily, Instant.now());
        events.publishEvent(new PasswordChanged(userId, Instant.now(), false));
    }

    /** Admin: suspend or re-activate an account. Suspension signs the user out everywhere. */
    @Transactional
    public void changeStatus(UUID userId, UserStatus status) {
        User user = load(userId);
        user.changeStatus(status);
        if (status != UserStatus.ACTIVE) {
            refreshTokens.revokeAllForUser(userId, null, Instant.now());
        }
    }

    /** One row in the admin user list. */
    public record UserRowDto(UUID id, String fullName, String email, String phone, String role, String status,
                             OffsetDateTime createdAt, long bookingCount) {
    }

    /** Admin: search users by role, status and name/phone/email. */
    @Transactional(readOnly = true)
    public PageResponse<UserRowDto> search(String role, String status, String q, int page, int size) {
        String where = """
             WHERE (CAST(:role AS varchar) IS NULL OR u.role = :role)
               AND (CAST(:status AS varchar) IS NULL OR u.status = :status)
               AND (CAST(:q AS varchar) IS NULL OR u.full_name ILIKE :q OR u.phone LIKE :q OR u.email::text ILIKE :q)
            """;
        String roleFilter = StringUtils.hasText(role) ? role.trim().toUpperCase(Locale.ROOT) : null;
        String statusFilter = StringUtils.hasText(status) ? status.trim().toUpperCase(Locale.ROOT) : null;
        String query = StringUtils.hasText(q) ? "%" + q.trim() + "%" : null;
        long total = jdbc.sql("SELECT count(*) FROM users u" + where)
            .param("role", roleFilter).param("status", statusFilter).param("q", query)
            .query(Long.class).single();
        var items = jdbc.sql("""
                SELECT u.id, u.full_name, u.email, u.phone, u.role, u.status, u.created_at,
                       (SELECT count(*) FROM bookings b WHERE b.customer_id = u.id OR b.genie_id = u.id) AS booking_count
                  FROM users u
                """ + where + " ORDER BY u.created_at DESC LIMIT :limit OFFSET :offset")
            .param("role", roleFilter).param("status", statusFilter).param("q", query)
            .param("limit", PageResponse.clampSize(size)).param("offset", PageResponse.offset(page, size))
            .query(UserRowDto.class).list();
        return new PageResponse<>(items, Math.max(page, 0), PageResponse.clampSize(size), total);
    }

    private User load(UUID userId) {
        return users.findById(userId).orElseThrow(() -> ApiException.notFound("USER_NOT_FOUND", "User not found"));
    }
}
