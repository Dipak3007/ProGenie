package com.progenie.provider;

import com.progenie.identity.UserRegisteredEvent;
import com.progenie.identity.domain.Role;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * When someone registers as a Genie, create their empty profile in REGISTERED state.
 * They then complete onboarding and wait for admin approval before they can be booked.
 * Runs synchronously inside the registration transaction, so both succeed or both fail.
 */
@Component
class GenieOnboardingListener {

    private static final Logger log = LoggerFactory.getLogger(GenieOnboardingListener.class);

    private final JdbcClient jdbc;

    GenieOnboardingListener(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @EventListener
    void on(UserRegisteredEvent event) {
        if (event.role() != Role.GENIE) {
            return;
        }
        jdbc.sql("""
                INSERT INTO genie_profiles (user_id, verification_status) VALUES (:id, 'REGISTERED')
                """)
            .param("id", event.userId())
            .update();
        jdbc.sql("""
                INSERT INTO genie_verification_events (genie_id, from_status, to_status, reason, actor_id)
                VALUES (:id, NULL, 'REGISTERED', 'Account created', :id)
                """)
            .param("id", event.userId())
            .update();
        log.info("Created Genie profile for {}", event.userId());
    }
}
