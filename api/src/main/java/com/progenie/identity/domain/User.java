package com.progenie.identity.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

/** Every account (customer, Genie, admin). Maps the {@code users} table created by Flyway V1. */
@Entity
@Table(name = "users")
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(unique = true)
    private String email;

    @Column(unique = true)
    private String phone;                      // null only for a deleted (anonymised) account

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Column(name = "full_name", nullable = false)
    private String fullName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Role role;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private UserStatus status = UserStatus.ACTIVE;

    @Column(name = "email_verified", nullable = false)
    private boolean emailVerified;

    @Column(name = "phone_verified", nullable = false)
    private boolean phoneVerified;

    @Column(name = "phone_verified_at")
    private Instant phoneVerifiedAt;

    @Column(name = "email_verified_at")
    private Instant emailVerifiedAt;

    @Column(name = "password_locked_until")
    private Instant passwordLockedUntil;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private int version;

    protected User() {
        // for JPA
    }

    public User(String fullName, String email, String phone, String passwordHash, Role role) {
        this.fullName = fullName;
        this.email = email;
        this.phone = phone;
        this.passwordHash = passwordHash;
        this.role = role;
    }

    public boolean isActive() {
        return status == UserStatus.ACTIVE;
    }

    /** A changed email address must be verified again. */
    public void updateProfile(String fullName, String email) {
        if (email == null ? this.email != null : !email.equalsIgnoreCase(this.email)) {
            this.emailVerified = false;
            this.emailVerifiedAt = null;
        }
        this.fullName = fullName;
        this.email = email;
    }

    public void markPhoneVerified(Instant at) {
        if (!phoneVerified) {
            phoneVerified = true;
            phoneVerifiedAt = at;
        }
    }

    public void markEmailVerified(Instant at) {
        if (!emailVerified) {
            emailVerified = true;
            emailVerifiedAt = at;
        }
    }

    public boolean isPasswordLocked(Instant now) {
        return passwordLockedUntil != null && passwordLockedUntil.isAfter(now);
    }

    public void unlockPassword() {
        passwordLockedUntil = null;
    }

    /**
     * Account deletion: personal details are removed, the row stays so bookings, payments and reviews keep
     * pointing at it. The password is replaced by a random, unusable hash.
     */
    public void anonymise(String unusablePasswordHash, Instant at) {
        this.fullName = "Deleted user";
        this.email = null;
        this.phone = null;
        this.passwordHash = unusablePasswordHash;
        this.emailVerified = false;
        this.phoneVerified = false;
        this.emailVerifiedAt = null;
        this.phoneVerifiedAt = null;
        this.status = UserStatus.DELETED;
        this.deletedAt = at;
    }

    public void changePasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
    }

    public void changeStatus(UserStatus status) {
        this.status = status;
    }

    public UUID getId() {
        return id;
    }

    public String getEmail() {
        return email;
    }

    public String getPhone() {
        return phone;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public String getFullName() {
        return fullName;
    }

    public Role getRole() {
        return role;
    }

    public UserStatus getStatus() {
        return status;
    }

    public boolean isPhoneVerified() {
        return phoneVerified;
    }

    public boolean isEmailVerified() {
        return emailVerified;
    }

    public Instant getPasswordLockedUntil() {
        return passwordLockedUntil;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
