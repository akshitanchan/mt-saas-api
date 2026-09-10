package com.akshitanchan.saas.auth;

import com.akshitanchan.saas.config.AppProperties;
import com.auth0.jwt.JWT;
import com.auth0.jwt.JWTVerifier;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.interfaces.DecodedJWT;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.springframework.stereotype.Component;

// hs256 access tokens: sub is the user id, iss/aud are fixed, exp = iat + configured minutes
@Component
public class JwtService {

    private final Algorithm algorithm;
    private final String issuer;
    private final String audience;
    private final long expiresMinutes;
    private final JWTVerifier verifier;

    public JwtService(AppProperties properties) {
        AppProperties.Jwt jwt = properties.jwt();
        this.algorithm = Algorithm.HMAC256(jwt.secret());
        this.issuer = jwt.issuer();
        this.audience = jwt.audience();
        this.expiresMinutes = jwt.expiresMinutes();
        this.verifier = JWT.require(algorithm)
                .withIssuer(issuer)
                .withAudience(audience)
                .build();
    }

    public String mint(UUID subject) {
        Instant iat = Instant.now();
        return JWT.create()
                .withSubject(subject.toString())
                .withIssuer(issuer)
                .withAudience(audience)
                .withIssuedAt(iat)
                .withExpiresAt(iat.plus(expiresMinutes, ChronoUnit.MINUTES))
                .sign(algorithm);
    }

    // throws com.auth0.jwt.exceptions.JWTVerificationException (covers expiry, bad signature, bad claims)
    public DecodedJWT decode(String token) {
        return verifier.verify(token);
    }
}
