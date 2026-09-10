package com.akshitanchan.saas.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.akshitanchan.saas.config.AppProperties;
import com.auth0.jwt.interfaces.DecodedJWT;
import java.util.UUID;
import org.junit.jupiter.api.Test;

// plain unit test, no spring context needed: also prints a token minted with the
// secret "test-secret" so it can be decoded by hand on the python side, e.g.
// JWT_SECRET=test-secret ~/.venvs/mt-saas-api/bin/python -c
//   "from app.auth.tokens import decode_access_token; print(decode_access_token('<token>'))"
class JwtServiceTest {

    @Test
    void mintedTokenRoundTripsThroughDecodeAndCarriesTheExpectedClaims() {
        JwtService service = new JwtService(new AppProperties(
                "dev", "http://localhost:8000",
                new AppProperties.Jwt("test-secret", "mt-saas-api", "mt-saas-api", 60),
                null, null, null));

        UUID subject = UUID.randomUUID();
        String token = service.mint(subject);
        System.out.println("cross-decode token (JWT_SECRET=test-secret): " + token);

        DecodedJWT decoded = service.decode(token);
        assertThat(decoded.getSubject()).isEqualTo(subject.toString());
        assertThat(decoded.getIssuer()).isEqualTo("mt-saas-api");
        assertThat(decoded.getAudience()).containsExactly("mt-saas-api");
        assertThat(decoded.getExpiresAtAsInstant()).isAfter(decoded.getIssuedAtAsInstant());
    }
}
