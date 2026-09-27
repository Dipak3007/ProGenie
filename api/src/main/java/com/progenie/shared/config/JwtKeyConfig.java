package com.progenie.shared.config;

import java.io.ByteArrayInputStream;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.util.Base64;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.converter.RsaKeyConverters;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.util.StringUtils;

/**
 * RSA key pair used to sign (encoder) and verify (decoder) access tokens with RS256.
 *
 * <p>Locally, if no keys are configured, a temporary pair is generated at startup.
 * In production set PROGENIE_JWT_PRIVATE_KEY / PROGENIE_JWT_PUBLIC_KEY (base64 of the PEM files)
 * so every API replica shares the same key and tokens survive restarts.
 */
@Configuration
public class JwtKeyConfig {

    private static final Logger log = LoggerFactory.getLogger(JwtKeyConfig.class);

    private final RSAPublicKey publicKey;
    private final RSAPrivateKey privateKey;

    public JwtKeyConfig(AppProperties props) {
        AppProperties.Jwt jwt = props.jwt();
        if (StringUtils.hasText(jwt.privateKey()) && StringUtils.hasText(jwt.publicKey())) {
            this.privateKey = RsaKeyConverters.pkcs8().convert(pem(jwt.privateKey()));
            this.publicKey = RsaKeyConverters.x509().convert(pem(jwt.publicKey()));
        } else {
            log.warn("No JWT keys configured: generating a temporary RSA key pair. "
                    + "Tokens become invalid after restart. Configure keys for any shared environment.");
            KeyPair pair = generate();
            this.privateKey = (RSAPrivateKey) pair.getPrivate();
            this.publicKey = (RSAPublicKey) pair.getPublic();
        }
    }

    @Bean
    JwtEncoder jwtEncoder() {
        return NimbusJwtEncoder.withKeyPair(publicKey, privateKey).build();
    }

    @Bean
    JwtDecoder jwtDecoder(AppProperties props) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey(publicKey).build();
        decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(props.jwt().issuer()));
        return decoder;
    }

    private static ByteArrayInputStream pem(String base64Pem) {
        return new ByteArrayInputStream(Base64.getDecoder().decode(base64Pem.trim()));
    }

    private static KeyPair generate() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("RSA not available", e);
        }
    }
}
