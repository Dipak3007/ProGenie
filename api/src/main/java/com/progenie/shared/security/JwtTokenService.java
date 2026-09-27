package com.progenie.shared.security;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.progenie.shared.config.AppProperties;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

/** Issues short-lived RS256 access tokens. Claims: sub = user id, roles = [ROLE], name. */
@Service
public class JwtTokenService {

    private final JwtEncoder encoder;
    private final AppProperties.Jwt jwt;

    public JwtTokenService(JwtEncoder encoder, AppProperties props) {
        this.encoder = encoder;
        this.jwt = props.jwt();
    }

    public String issueAccessToken(UUID userId, String role, String fullName) {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
            .issuer(jwt.issuer())
            .issuedAt(now)
            .expiresAt(now.plus(jwt.accessTokenTtl()))
            .subject(userId.toString())
            .id(UUID.randomUUID().toString())
            .claim("roles", List.of(role))
            .claim("name", fullName)
            .build();
        JwsHeader header = JwsHeader.with(SignatureAlgorithm.RS256).build();
        return encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }

    public Duration accessTokenTtl() {
        return jwt.accessTokenTtl();
    }
}
