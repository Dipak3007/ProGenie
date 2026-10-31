package com.progenie.booking;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code progenie.instant.*}: Genie Now, the nearest free Genie right away (Phase 3C-9).
 *
 * @param offerTimeout      how long one Genie has to accept the offer before the next one is asked
 * @param maxGenies         how many Genies are asked, one after another, before giving up
 * @param busyWindow        a Genie with a booking (or time off) within this long from now is not asked
 * @param arrivalWindow     the Genie arrives within this; the booking blocks now → now + this + the job's duration
 * @param positionMaxAge    a Genie's live position younger than this (and accurate to 1 km) is used for "nearest"; else their base
 * @param sweepInterval     how often expired offers are moved on to the next Genie
 */
@ConfigurationProperties(prefix = "progenie.instant")
public record InstantProperties(
    @DefaultValue("45s") Duration offerTimeout,
    @DefaultValue("5") int maxGenies,
    @DefaultValue("2h") Duration busyWindow,
    @DefaultValue("60m") Duration arrivalWindow,
    @DefaultValue("15m") Duration positionMaxAge,
    @DefaultValue("5s") Duration sweepInterval) {
}
