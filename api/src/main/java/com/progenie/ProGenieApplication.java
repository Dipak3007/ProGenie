package com.progenie;

import java.util.TimeZone;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

import com.progenie.shared.config.DeploymentSafetyCheck;

/**
 * ProGenie 2.0 API: a modular monolith.
 *
 * <p>Each direct sub-package (identity, catalog, provider, booking, ...) is a business module.
 * Modules talk to each other through public services or application events, never through
 * another module's repositories. See docs/LOCAL_SETUP.md for the full structure.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class ProGenieApplication {

    public static void main(String[] args) {
        // The server always runs in UTC (timestamps are stored as timestamptz). Business rules that depend on
        // local time (slots, payout weeks) use progenie.timezone explicitly. This also avoids JDBC sessions being
        // opened with an OS time zone name the database may not know (e.g. the legacy "Asia/Calcutta").
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
        SpringApplication app = new SpringApplication(ProGenieApplication.class);
        // On a server (PROGENIE_ENVIRONMENT=sit/uat/prod) refuse to start with local development defaults.
        app.addListeners(new DeploymentSafetyCheck());
        app.run(args);
    }
}
