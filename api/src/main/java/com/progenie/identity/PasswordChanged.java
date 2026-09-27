package com.progenie.identity;

import java.time.Instant;
import java.util.UUID;

/** A password was changed or reset; every session was signed out. Triggers a security notice. */
public record PasswordChanged(UUID userId, Instant at, boolean viaReset) {
}
