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

    /** KYC files. {@code localDir} is used by the local-disk storage adapter (S3 adapter comes later). */
    public record Storage(String localDir, long maxUploadBytes) {
    }
}
