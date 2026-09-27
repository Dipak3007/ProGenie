package com.progenie.shared.security;

import java.util.UUID;

import com.progenie.shared.error.ApiException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;

/** Small helper to read the logged-in user from the validated JWT. */
public final class CurrentUser {

    private CurrentUser() {
    }

    public static UUID id() {
        return UUID.fromString(jwt().getSubject());
    }

    /** The logged-in user, or null on a public endpoint called without a token. */
    public static UUID idOrNull() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getPrincipal() instanceof Jwt jwt ? UUID.fromString(jwt.getSubject()) : null;
    }

    /** CUSTOMER, GENIE or ADMIN. */
    public static String role() {
        var roles = jwt().getClaimAsStringList("roles");
        return roles == null || roles.isEmpty() ? "" : roles.getFirst();
    }

    private static Jwt jwt() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof Jwt jwt) {
            return jwt;
        }
        throw ApiException.unauthorized("NOT_AUTHENTICATED", "Please log in");
    }
}
