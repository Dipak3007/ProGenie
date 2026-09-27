package com.progenie.shared.util;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/**
 * JDBC helpers. The PostgreSQL driver binds {@link OffsetDateTime} to timestamptz but not
 * {@link Instant}, so JdbcClient parameters go through {@link #odt(Instant)}.
 */
public final class Times {

    private Times() {
    }

    public static OffsetDateTime odt(Instant instant) {
        return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
    }
}
