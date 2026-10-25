package com.progenie.it;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.TimeZone;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Base for integration tests ({@code *IT.java}, run by {@code mvn verify}): the whole application on a random
 * port against a real PostgreSQL + PostGIS, migrated by Flyway from ProGenie_DB_Scripts like production.
 *
 * <p>The database is a Testcontainers container (Docker needed), started once and shared by every IT class.
 * Without Docker, point the tests at an empty database instead:
 * {@code PROGENIE_IT_JDBC_URL=jdbc:postgresql://localhost:5432/progenie_it} (plus PROGENIE_IT_DB_USER /
 * PROGENIE_IT_DB_PASSWORD, default progenie/progenie). Migrations come from {@code PROGENIE_DB_MIGRATIONS}
 * or the sibling folder {@code ../../ProGenie_DB_Scripts/migrations}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "progenie.messaging.dispatch-interval=1h",      // no Mailpit needed: messages stay in the outbox
    "progenie.privacy.export-interval=1h",
    "progenie.geocoding.provider=none",            // no outside calls (GeoLookupIT uses a stand-in)
    "progenie.storage.type=local",
    "progenie.storage.local-dir=target/it-uploads",
    "progenie.booking.min-lead-time=5m",
    "logging.level.com.progenie=INFO",
})
public abstract class IntegrationTest {

    private static final String EXTERNAL_URL = System.getenv("PROGENIE_IT_JDBC_URL");
    private static final PostgreSQLContainer DB;

    static {
        // Like ProGenieApplication.main: the server runs in UTC (and PostgreSQL rejects legacy zone names)
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
        if (EXTERNAL_URL == null || EXTERNAL_URL.isBlank()) {
            DB = new PostgreSQLContainer(DockerImageName.parse("postgis/postgis:17-3.5").asCompatibleSubstituteFor("postgres"))
                .withDatabaseName("progenie").withUsername("progenie").withPassword("progenie");
            DB.start(); // stopped by Testcontainers when the test JVM exits
        } else {
            DB = null;
        }
    }

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        if (DB != null) {
            registry.add("spring.datasource.url", () -> DB.getJdbcUrl() + (DB.getJdbcUrl().contains("?") ? "&" : "?") + "stringtype=unspecified");
            registry.add("spring.datasource.username", DB::getUsername);
            registry.add("spring.datasource.password", DB::getPassword);
        } else {
            registry.add("spring.datasource.url", () -> EXTERNAL_URL + (EXTERNAL_URL.contains("?") ? "&" : "?") + "stringtype=unspecified");
            registry.add("spring.datasource.username", () -> env("PROGENIE_IT_DB_USER", "progenie"));
            registry.add("spring.datasource.password", () -> env("PROGENIE_IT_DB_PASSWORD", "progenie"));
        }
        registry.add("spring.flyway.locations", IntegrationTest::migrations);
    }

    private static String migrations() {
        String configured = System.getenv("PROGENIE_DB_MIGRATIONS");
        if (configured != null && !configured.isBlank()) {
            return configured;
        }
        Path sibling = Path.of("../../ProGenie_DB_Scripts/migrations");
        if (!Files.isDirectory(sibling)) {
            throw new IllegalStateException("Migrations not found at " + sibling.toAbsolutePath()
                + ". Check out ProGenie_DB_Scripts next to ProGenie, or set PROGENIE_DB_MIGRATIONS=filesystem:/path/to/migrations");
        }
        return "filesystem:" + sibling;
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}
