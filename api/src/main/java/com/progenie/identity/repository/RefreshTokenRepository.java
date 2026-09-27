package com.progenie.identity.repository;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import com.progenie.identity.domain.RefreshToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {

    Optional<RefreshToken> findByTokenHash(String tokenHash);

    @Modifying
    @Query("update RefreshToken t set t.revokedAt = :now where t.familyId = :familyId and t.revokedAt is null")
    int revokeFamily(@Param("familyId") UUID familyId, @Param("now") Instant now);

    /** Logs the user out everywhere, optionally keeping one login (the current device). */
    @Modifying
    @Query("""
        update RefreshToken t set t.revokedAt = :now
         where t.userId = :userId and t.revokedAt is null and (:keepFamilyId is null or t.familyId <> :keepFamilyId)
        """)
    int revokeAllForUser(@Param("userId") UUID userId, @Param("keepFamilyId") UUID keepFamilyId, @Param("now") Instant now);
}
