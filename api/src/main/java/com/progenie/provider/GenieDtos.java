package com.progenie.provider;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** Read models for Genie listing and profile pages. */
public final class GenieDtos {

    private GenieDtos() {
    }

    /** One card in the Genie listing. */
    public record GenieCardDto(UUID id, String fullName, String bio, int experienceYears,
                               BigDecimal avgRating, int ratingCount, int completedJobs,
                               String baseArea, boolean online, BigDecimal startingPrice,
                               String categories, String categorySlug, String coverImage) {
    }

    public record GenieServiceDto(long serviceId, String name, BigDecimal price, int durationMinutes) {
    }

    public record ReviewDto(int rating, String comment, String customerName, OffsetDateTime createdAt, String genieReply) {
    }

    public record GenieDetailDto(GenieCardDto genie, List<GenieServiceDto> services, List<ReviewDto> reviews) {
    }
}
