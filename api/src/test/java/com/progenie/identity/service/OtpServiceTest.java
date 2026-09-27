package com.progenie.identity.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.util.UUID;

import com.progenie.shared.error.ApiException;
import org.junit.jupiter.api.Test;

class OtpServiceTest {

    private static AuthProperties props(String pepper) {
        return new AuthProperties(pepper, Duration.ofMinutes(5), 5, Duration.ofSeconds(30), 5, 20, Duration.ofMinutes(15),
            10, Duration.ofMinutes(15), Duration.ofMinutes(15), true);
    }

    @Test
    void codesAreStoredAsAPepperedHmacTiedToTheChallenge() {
        OtpService a = new OtpService(null, null, null, null, props("pepper-one"), Clock.systemUTC());
        OtpService b = new OtpService(null, null, null, null, props("pepper-two"), Clock.systemUTC());
        UUID id = UUID.randomUUID();
        assertThat(a.hmac(id, "123456")).hasSize(64).isEqualTo(a.hmac(id, "123456"))
            .isNotEqualTo(a.hmac(UUID.randomUUID(), "123456"))
            .isNotEqualTo(b.hmac(id, "123456"))
            .doesNotContain("123456");
    }

    @Test
    void identifiersAreNormalised() {
        assertThat(OtpService.normalise(" Customer@Example.COM ")).isEqualTo("customer@example.com");
        assertThat(OtpService.normalise("+91 98250 00005")).isEqualTo("9825000005");
        assertThatThrownBy(() -> OtpService.normalise("12345")).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> OtpService.normalise("not@valid")).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> OtpService.normalise(" ")).isInstanceOf(ApiException.class);
    }
}
