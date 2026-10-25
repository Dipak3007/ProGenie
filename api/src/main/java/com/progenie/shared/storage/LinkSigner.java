package com.progenie.shared.storage;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import com.progenie.shared.config.AppProperties;
import com.progenie.shared.error.ApiException;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Signs local download links: {@code /api/v1/files/{payload}.{hmac}}. The payload (key, file name, type,
 * disposition, expiry) is readable but cannot be changed or reused after it expires. With no
 * {@code progenie.storage.link-secret} a random key is made at start-up, so links die with a restart (they only
 * live a few minutes anyway).
 */
@Component
public class LinkSigner {

    /** What a link points at. {@code e} is the expiry in epoch seconds. */
    public record Target(String k, String n, String t, boolean i, long e) {
    }

    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder B64D = Base64.getUrlDecoder();

    private final byte[] secret;
    private final JsonMapper json = JsonMapper.builder().build();
    private final Clock clock;

    public LinkSigner(AppProperties props, Clock clock) {
        String configured = props.storage().linkSecret();
        if (configured != null && !configured.isBlank()) {
            this.secret = configured.getBytes(StandardCharsets.UTF_8);
        } else {
            this.secret = new byte[32];
            new SecureRandom().nextBytes(this.secret);
        }
        this.clock = clock;
    }

    public String sign(Target target) {
        String payload = B64.encodeToString(json.writeValueAsBytes(target));
        return payload + "." + B64.encodeToString(hmac(payload));
    }

    /** The target of a valid, unexpired token; 404 for anything else (no hint whether it was forged or old). */
    public Target verify(String token) {
        int dot = token == null ? -1 : token.indexOf('.');
        if (dot <= 0) {
            throw invalid();
        }
        String payload = token.substring(0, dot);
        byte[] given;
        try {
            given = B64D.decode(token.substring(dot + 1));
        } catch (IllegalArgumentException e) {
            throw invalid();
        }
        if (!MessageDigest.isEqual(hmac(payload), given)) {
            throw invalid();
        }
        Target t;
        try {
            t = json.readValue(B64D.decode(payload), Target.class);
        } catch (RuntimeException e) {
            throw invalid();
        }
        if (Instant.ofEpochSecond(t.e()).isBefore(clock.instant())) {
            throw invalid();
        }
        return t;
    }

    private byte[] hmac(String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return mac.doFinal(payload.getBytes(StandardCharsets.US_ASCII));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static ApiException invalid() {
        return ApiException.notFound("LINK_EXPIRED", "This download link has expired. Open the file again from the app.");
    }
}
