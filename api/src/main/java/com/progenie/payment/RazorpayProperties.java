package com.progenie.payment;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code progenie.payments.razorpay.*}: used when {@code progenie.payments.gateway=razorpay}.
 * Put the keys in infra/.env (never commit them). Test keys start with rzp_test_.
 */
@ConfigurationProperties(prefix = "progenie.payments.razorpay")
public record RazorpayProperties(String keyId, String keySecret, String webhookSecret,
                                 @DefaultValue("https://api.razorpay.com") String baseUrl) {
}
