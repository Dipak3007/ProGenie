package com.progenie.shared.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class DeploymentSafetyCheckTest {

    /** What application.yml resolves to on a laptop with no environment variables. */
    private static MockEnvironment localDefaults() {
        return new MockEnvironment()
                .withProperty("spring.datasource.password", "progenie")
                .withProperty("progenie.auth.otp-pepper", DeploymentSafetyCheck.DEV_OTP_PEPPER)
                .withProperty("progenie.payments.webhook-secret", DeploymentSafetyCheck.DEV_WEBHOOK_SECRET)
                .withProperty("progenie.jwt.private-key", "")
                .withProperty("progenie.jwt.public-key", "")
                .withProperty("progenie.cookie.secure", "false")
                .withProperty("progenie.messaging.web-base-url", "http://localhost:4200")
                .withProperty("progenie.payments.gateway", "fake");
    }

    private static MockEnvironment configuredSit() {
        return new MockEnvironment()
                .withProperty("progenie.environment", "sit")
                .withProperty("spring.datasource.password", "a-strong-db-password")
                .withProperty("progenie.auth.otp-pepper", "p".repeat(32))
                .withProperty("progenie.payments.webhook-secret", "w".repeat(24))
                .withProperty("progenie.jwt.private-key", "cHJpdmF0ZQ==")
                .withProperty("progenie.jwt.public-key", "cHVibGlj")
                .withProperty("progenie.cookie.secure", "true")
                .withProperty("progenie.messaging.web-base-url", "https://sit.example.in")
                .withProperty("progenie.payments.gateway", "fake");
    }

    @Test
    void localDefaultsAreAllowedLocally() {
        assertThat(DeploymentSafetyCheck.problems(localDefaults())).isEmpty();
    }

    @Test
    void localDefaultsAreRefusedOnSit() {
        var problems = DeploymentSafetyCheck.problems(localDefaults().withProperty("progenie.environment", "SIT"));
        assertThat(problems).hasSize(6);
        assertThat(String.join("\n", problems))
                .contains("DB_PASSWORD", "PROGENIE_OTP_PEPPER", "PROGENIE_PAYMENT_WEBHOOK_SECRET", "PROGENIE_JWT_PRIVATE_KEY",
                        "PROGENIE_COOKIE_SECURE", "PROGENIE_WEB_BASE_URL")
                // names only, never the values
                .doesNotContain(DeploymentSafetyCheck.DEV_OTP_PEPPER);
    }

    @Test
    void aConfiguredSitStarts() {
        assertThat(DeploymentSafetyCheck.problems(configuredSit())).isEmpty();
    }

    @Test
    void razorpayNeedsItsKeysAndSitRefusesLiveKeys() {
        var env = configuredSit().withProperty("progenie.payments.gateway", "razorpay");
        assertThat(DeploymentSafetyCheck.problems(env)).hasSize(3);
        env.withProperty("progenie.payments.razorpay.key-id", "rzp_live_abc")
                .withProperty("progenie.payments.razorpay.key-secret", "s")
                .withProperty("progenie.payments.razorpay.webhook-secret", "w");
        assertThat(DeploymentSafetyCheck.problems(env)).singleElement().asString().contains("live keys");
    }

    @Test
    void productionRefusesTheFakeGatewayAndMailpit() {
        var problems = DeploymentSafetyCheck.problems(configuredSit().withProperty("progenie.environment", "prod"));
        assertThat(String.join("\n", problems))
                .contains("PROGENIE_PAYMENT_GATEWAY", "PROGENIE_SMS_PROVIDER", "PROGENIE_WHATSAPP_PROVIDER", "PROGENIE_EMAIL_PROVIDER");
    }

    @Test
    void msg91NeedsAnAuthKey() {
        var env = configuredSit().withProperty("progenie.messaging.sms-provider", "msg91");
        assertThat(DeploymentSafetyCheck.problems(env)).singleElement().asString().contains("MSG91_AUTH_KEY");
    }
}
