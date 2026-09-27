package com.progenie.shared.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.core.env.Environment;

/**
 * Refuses to start a shared deployment (SIT, UAT, production) that still uses a local development default.
 *
 * <p>application.yml keeps convenient fallbacks so the API runs on a laptop with no setup (database password
 * "progenie", a known OTP pepper, a generated JWT key pair, insecure cookies). Those are fine locally and
 * dangerous anywhere reachable from the internet. Set {@code PROGENIE_ENVIRONMENT} to {@code sit}, {@code uat} or
 * {@code prod} on a server and this check runs before anything else, listing every setting still missing.
 * Values are never logged, only the names of the variables to set.
 *
 * <p>Registered in {@code ProGenieApplication.main}, so it runs before the database or Flyway are touched.
 */
public class DeploymentSafetyCheck implements ApplicationListener<ApplicationEnvironmentPreparedEvent> {

    static final String DEV_OTP_PEPPER = "local-dev-otp-pepper-change-me";
    static final String DEV_WEBHOOK_SECRET = "local-dev-webhook-secret";
    static final String DEV_DB_PASSWORD = "progenie";

    @Override
    public void onApplicationEvent(ApplicationEnvironmentPreparedEvent event) {
        Environment env = event.getEnvironment();
        List<String> problems = problems(env);
        if (!problems.isEmpty()) {
            throw new IllegalStateException("Refusing to start with PROGENIE_ENVIRONMENT=" + environment(env)
                    + ". Fix these settings (see docs/DEPLOYMENT.md):\n  - " + String.join("\n  - ", problems));
        }
    }

    static String environment(Environment env) {
        return env.getProperty("progenie.environment", "local").trim().toLowerCase(Locale.ROOT);
    }

    /** Everything wrong for the configured environment; empty when it is safe to start (always empty locally). */
    static List<String> problems(Environment env) {
        String name = environment(env);
        List<String> out = new ArrayList<>();
        if (name.equals("local") || name.equals("test")) {
            return out;
        }
        boolean prod = name.equals("prod") || name.equals("production");

        String dbPassword = env.getProperty("spring.datasource.password", "");
        if (dbPassword.equals(DEV_DB_PASSWORD) || dbPassword.length() < 12) {
            out.add("DB_PASSWORD: use a strong database password (12+ characters), not the local default");
        }
        String pepper = env.getProperty("progenie.auth.otp-pepper", "");
        if (pepper.equals(DEV_OTP_PEPPER) || pepper.length() < 32) {
            out.add("PROGENIE_OTP_PEPPER: set a random secret of 32+ characters (openssl rand -hex 32)");
        }
        String webhook = env.getProperty("progenie.payments.webhook-secret", "");
        if (webhook.equals(DEV_WEBHOOK_SECRET) || webhook.length() < 16) {
            out.add("PROGENIE_PAYMENT_WEBHOOK_SECRET: set a random secret of 16+ characters (openssl rand -hex 24)");
        }
        if (blank(env, "progenie.jwt.private-key") || blank(env, "progenie.jwt.public-key")) {
            out.add("PROGENIE_JWT_PRIVATE_KEY / PROGENIE_JWT_PUBLIC_KEY: set a key pair so logins survive restarts");
        }
        if (!env.getProperty("progenie.cookie.secure", Boolean.class, false)) {
            out.add("PROGENIE_COOKIE_SECURE: must be true (the site has to be served over HTTPS)");
        }
        String webUrl = env.getProperty("progenie.messaging.web-base-url", "");
        if (!webUrl.startsWith("https://")) {
            out.add("PROGENIE_WEB_BASE_URL: set the public https:// address (used in links inside messages)");
        }

        String gateway = env.getProperty("progenie.payments.gateway", "fake");
        if (gateway.equals("razorpay")) {
            for (String key : List.of("key-id", "key-secret", "webhook-secret")) {
                if (blank(env, "progenie.payments.razorpay." + key)) {
                    out.add("RAZORPAY_" + key.replace('-', '_').toUpperCase(Locale.ROOT) + ": required when PROGENIE_PAYMENT_GATEWAY=razorpay");
                }
            }
            if (!prod && env.getProperty("progenie.payments.razorpay.key-id", "").startsWith("rzp_live_")) {
                out.add("RAZORPAY_KEY_ID: live keys are only allowed with PROGENIE_ENVIRONMENT=prod; use rzp_test_ keys");
            }
        } else if (prod) {
            out.add("PROGENIE_PAYMENT_GATEWAY: the fake gateway is not allowed in production");
        }

        boolean usesMsg91 = false;
        for (String channel : List.of("sms", "whatsapp", "email")) {
            String provider = env.getProperty("progenie.messaging." + channel + "-provider", "mailpit");
            usesMsg91 |= provider.equals("msg91");
            if (prod && provider.equals("mailpit")) {
                out.add("PROGENIE_" + channel.toUpperCase(Locale.ROOT) + "_PROVIDER: Mailpit is not allowed in production");
            }
        }
        if (usesMsg91 && blank(env, "progenie.messaging.msg91.auth-key")) {
            out.add("MSG91_AUTH_KEY: required when a channel uses msg91");
        }
        return out;
    }

    private static boolean blank(Environment env, String key) {
        String value = env.getProperty(key);
        return value == null || value.isBlank();
    }
}
