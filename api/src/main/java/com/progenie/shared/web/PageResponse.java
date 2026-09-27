package com.progenie.shared.web;

import java.util.List;

/** One page of results. {@code page} is zero-based. */
public record PageResponse<T>(List<T> items, int page, int size, long total) {

    public static int clampSize(int size) {
        return Math.clamp(size, 1, 100);
    }

    public static int offset(int page, int size) {
        return Math.max(page, 0) * clampSize(size);
    }
}
