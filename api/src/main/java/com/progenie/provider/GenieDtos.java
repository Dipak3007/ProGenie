package com.progenie.provider;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

/** Read models for Genie listing and profile pages. */
public final class GenieDtos {

    private GenieDtos() {
    }

    /** One card in the Genie listing. */
    public record GenieCardDto(UUID id, String fullName, @Nullable String bio, int experienceYears,
                               BigDecimal avgRating, int ratingCount, int completedJobs,
                               @Nullable String baseArea, boolean online, BigDecimal startingPrice,
                               String categories, String categorySlug, @Nullable String coverImage,
                               @Nullable String citySlug, @Nullable String cityName) {
    }

    public record GenieServiceDto(long serviceId, String name, BigDecimal price, int durationMinutes) {
    }

    public record GenieReviewDto(int rating, @Nullable String comment, String customerName, OffsetDateTime createdAt, @Nullable String genieReply) {
    }

    public record GenieDetailDto(GenieCardDto genie, List<GenieServiceDto> services, List<GenieReviewDto> reviews) {
    }
}
