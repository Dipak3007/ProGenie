package com.progenie.shared.config;

import java.time.Clock;
import java.time.ZoneId;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Background jobs (booking expiry, weekly payouts) and a single injectable {@link Clock},
 * so time-based rules can be tested with a fixed clock.
 */
@Configuration
@EnableScheduling
public class SchedulingConfig {

    @Bean
    Clock clock(AppProperties props) {
        return Clock.system(ZoneId.of(props.timezone()));
    }
}
