package com.progenie.identity.repository;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import com.progenie.identity.domain.RefreshToken;
import com.progenie.shared.config.QueryConstants;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {

    Optional<RefreshToken> findByTokenHash(String tokenHash);

    @Modifying
    @Query(name = QueryConstants.UPDATE_REFRESH_TOKEN_REVOKE_FAMILY)
    int revokeFamily(@Param("familyId") UUID familyId, @Param("now") Instant now);

    /** Logs the user out everywhere, optionally keeping one login (the current device). */
    @Modifying
    @Query(name = QueryConstants.UPDATE_REFRESH_TOKEN_REVOKE_ALL_FOR_USER)
    int revokeAllForUser(@Param("userId") UUID userId, @Param("keepFamilyId") UUID keepFamilyId, @Param("now") Instant now);
}
