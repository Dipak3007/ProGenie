package com.progenie.shared.config;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Typed view of the {@code progenie.*} block in application.yml. */
@ConfigurationProperties(prefix = "progenie")
public record AppProperties(String timezone, Jwt jwt, Cookie cookie, Cors cors,
                            Booking booking, Payments payments, Storage storage) {

    public record Jwt(String issuer, Duration accessTokenTtl, Duration refreshTokenTtl,
                      String privateKey, String publicKey) {
    }

    public record Cookie(boolean secure) {
    }

    public record Cors(List<String> allowedOrigins) {
    }

    /**
     * Booking rules.
     *
     * @param requestTtl                    how long a Genie has to accept a request
     * @param minLeadTime                   earliest bookable slot = now + this
     * @param horizonDays                   how many days ahead customers can book
     * @param slotStepMinutes               slot start times are aligned to this step
     * @param startEarlyWindow              a Genie may start the job this long before the slot
     * @param freeCancellationWindow        customer cancellation is free until this long before the slot
     * @param lateCancellationFee           fee (INR) for a later cancellation; it goes to the Genie
     * @param genieCancellationFlagThreshold Genie cancellations within the window that flag the Genie to admin
     * @param genieCancellationFlagWindow   look-back window for the threshold above
     * @param maxReschedules                how many times a customer can move one booking
     */
    public record Booking(Duration requestTtl, Duration minLeadTime, int horizonDays, int slotStepMinutes,
                          Duration startEarlyWindow, Duration freeCancellationWindow, BigDecimal lateCancellationFee,
                          int genieCancellationFlagThreshold, Duration genieCancellationFlagWindow,
                          int maxReschedules) {
    }

    /** @param gateway "fake" locally; "razorpay" once a real gateway adapter is added */
    public record Payments(String gateway, String webhookSecret, String currency) {
    }

    /**
     * Uploaded and generated files (KYC, ticket photos, receipts, data exports).
     * {@code type}: {@code local} (disk under {@code localDir}, the default) or {@code s3} (any S3-compatible store:
     * Supabase Storage, SeaweedFS, AWS S3). Downloads use short-lived links valid for {@code linkTtl}.
     */
    public record Storage(String type, String localDir, long maxUploadBytes, Duration linkTtl, String linkSecret, S3 s3) {

        public boolean isS3() {
            return "s3".equalsIgnoreCase(type);
        }
    }

    /**
     * S3-compatible bucket. {@code endpoint} without the bucket, e.g. https://abc.supabase.co/storage/v1/s3 or
     * http://localhost:8333. {@code pathStyle}: bucket in the path (Supabase, SeaweedFS) instead of the host name.
     * {@code publicEndpoint}: the address browsers use for download links, when it differs from the one the API
     * uses (Docker: the API talks to http://s3:8333, the browser to http://localhost:8333). Empty = same.
     * {@code createBucket}: create the bucket at start-up if it is missing (local SeaweedFS).
     */
    public record S3(String endpoint, String publicEndpoint, String region, String bucket, String accessKey,
                     String secretKey, boolean pathStyle, boolean createBucket) {
    }
}
