package com.progenie.provider;

import java.math.BigDecimal;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Requests and read models for the Genie's own onboarding and profile screens. */
public final class GenieProfileDtos {

    private GenieProfileDtos() {
    }

    /** Onboarding steps a Genie must finish before submitting for verification. */
    public enum Step { PROFILE, LOCATION, SERVICES, AVAILABILITY, ID_DOCUMENT, SELFIE, PAYOUT }

    public record MyProfileDto(UUID id, String fullName, String phone, String email, String bio, int experienceYears,
                               String verificationStatus, String verificationNote, String baseArea,
                               Double baseLat, Double baseLng, int serviceRadiusKm, String payoutUpiId, boolean online,
                               BigDecimal avgRating, int ratingCount, int completedJobs, int cancellationCount,
                               OffsetDateTime submittedAt, OffsetDateTime approvedAt,
                               List<MyServiceDto> services, List<AvailabilityDto> availability,
                               List<DocumentDto> documents, List<Step> missingSteps, boolean canSubmit) {
    }

    /** Flat row read from genie_profiles + users; lists are loaded separately. */
    record ProfileRow(UUID id, String fullName, String phone, String email, String bio, int experienceYears,
                      String verificationStatus, String verificationNote, String baseArea, Double baseLat,
                      Double baseLng, int serviceRadiusKm, String payoutUpiId, boolean online, BigDecimal avgRating,
                      int ratingCount, int completedJobs, int cancellationCount, OffsetDateTime submittedAt,
                      OffsetDateTime approvedAt) {
    }

    public record ProfileRequest(
        @Size(max = 600) String bio,
        @Min(0) @Max(60) int experienceYears,
        @Size(max = 80) String baseArea,
        @NotNull @DecimalMin("-90") @DecimalMax("90") Double baseLat,
        @NotNull @DecimalMin("-180") @DecimalMax("180") Double baseLng,
        @Min(1) @Max(50) int serviceRadiusKm,
        @Pattern(regexp = "^[a-zA-Z0-9._-]{2,256}@[a-zA-Z]{2,64}$", message = "must be a valid UPI ID like name@bank")
        String payoutUpiId) {
    }

    public record MyServiceDto(long serviceId, String name, String categoryName, BigDecimal basePrice,
                               BigDecimal priceOverride, BigDecimal price, int durationMinutes) {
    }

    public record ServiceOfferRequest(@NotNull Long serviceId, @DecimalMin("1") BigDecimal priceOverride) {
    }

    public record ServicesRequest(@NotNull @Size(min = 1, max = 30) List<@Valid ServiceOfferRequest> services) {
    }

    public record AvailabilityDto(int dayOfWeek, LocalTime startTime, LocalTime endTime) {
    }

    public record AvailabilityRequest(@NotNull @Size(min = 1, max = 21) List<@Valid AvailabilityDto> windows) {
    }

    public record TimeOffDto(long id, OffsetDateTime startsAt, OffsetDateTime endsAt, String reason) {
    }

    public record TimeOffRequest(@NotNull OffsetDateTime startsAt, @NotNull OffsetDateTime endsAt,
                                 @Size(max = 200) String reason) {
    }

    public record DocumentDto(UUID id, String docType, String maskedNumber, String status, String fileName,
                              String contentType, Integer sizeBytes, String reviewNote, OffsetDateTime createdAt) {
    }

    public record OnlineRequest(boolean online) {
    }

    public record LocationRequest(@NotNull @DecimalMin("-90") @DecimalMax("90") Double lat,
                                  @NotNull @DecimalMin("-180") @DecimalMax("180") Double lng,
                                  @Min(0) @Max(10000) Integer accuracyM) {
    }

    public record ReplyRequest(@NotBlank @Size(max = 600) String reply) {
    }
}
