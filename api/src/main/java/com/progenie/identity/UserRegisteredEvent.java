package com.progenie.identity;

import java.util.UUID;

import com.progenie.identity.domain.Role;

/**
 * Published by the identity module after a new account is saved.
 * Other modules react to it (e.g. provider creates an empty Genie profile) without identity
 * knowing about them. This is the seam where a message broker can be added later.
 */
public record UserRegisteredEvent(UUID userId, Role role, String fullName) {
}
