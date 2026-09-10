package com.akshitanchan.saas.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.akshitanchan.saas.AbstractIntegrationTest;
import com.akshitanchan.saas.config.AppProperties;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

class AuthEndpointTest extends AbstractIntegrationTest {

    private static final ParameterizedTypeReference<Map<String, Object>> MAP_BODY = new ParameterizedTypeReference<>() {
    };

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private AuthMagicLinkRepository magicLinkRepository;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private AppProperties properties;

    @Test
    void requestLinkReturnsAnOpaqueTokenInNonProd() {
        Map<String, Object> body = requestLink(uniqueEmail());

        assertThat(body).containsEntry("sent", true);
        assertThat(body).containsEntry("link", null);
        assertThat((String) body.get("token")).isNotBlank();
    }

    @Test
    void redeemingAnUnknownTokenFails() {
        ResponseEntity<Map<String, Object>> response = redeemRaw("not-a-real-token");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isEqualTo(Map.of("detail", "invalid token"));
    }

    @Test
    void redeemingATokenTwiceFailsOnTheSecondAttempt() {
        String token = (String) requestLink(uniqueEmail()).get("token");

        ResponseEntity<Map<String, Object>> first = redeemRaw(token);
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(first.getBody()).containsKeys("access_token", "token_type");
        assertThat(first.getBody().get("token_type")).isEqualTo("bearer");

        ResponseEntity<Map<String, Object>> second = redeemRaw(token);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(second.getBody()).isEqualTo(Map.of("detail", "token already used"));
    }

    @Test
    void redeemingAnExpiredLinkFails() {
        String email = uniqueEmail();
        String token = (String) requestLink(email).get("token");

        // rewrite the row with an expiry in the past, directly through the repository
        User user = userRepository.findByEmail(email).orElseThrow();
        String tokenHash = MagicLinkTokens.hash(properties.magicLink().pepper(), token);
        magicLinkRepository.deleteById(tokenHash);
        magicLinkRepository.save(new AuthMagicLink(tokenHash, user.getId(), OffsetDateTime.now().minusMinutes(1)));

        ResponseEntity<Map<String, Object>> response = redeemRaw(token);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isEqualTo(Map.of("detail", "token expired"));
    }

    @Test
    void missingAuthorizationHeaderOnProtectedPathIsRejected() {
        ResponseEntity<Map<String, Object>> response = protectedRequest(null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody()).isEqualTo(Map.of("detail", "missing bearer token"));
    }

    @Test
    void junkBearerTokenIsRejected() {
        ResponseEntity<Map<String, Object>> response = protectedRequest("Bearer not-a-real-jwt");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody()).isEqualTo(Map.of("detail", "invalid token"));
    }

    @Test
    void validJwtForAUserThatIsNotInTheDatabaseIsRejected() {
        String jwt = jwtService.mint(UUID.randomUUID());

        ResponseEntity<Map<String, Object>> response = protectedRequest("Bearer " + jwt);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody()).isEqualTo(Map.of("detail", "user not found"));
    }

    @Test
    void bearerSchemeIsCaseInsensitive() {
        String token = (String) requestLink(uniqueEmail()).get("token");
        String jwt = (String) redeemRaw(token).getBody().get("access_token");

        ResponseEntity<Map<String, Object>> response = protectedRequest("bearer " + jwt);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo(Map.of("ok", true));
    }

    @Test
    void malformedEmailReturns422WithAListDetail() {
        ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                "/auth/request-link", HttpMethod.POST,
                new HttpEntity<>(Map.of("email", "not-an-email")),
                MAP_BODY);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(response.getBody().get("detail")).isInstanceOf(List.class);
    }

    private Map<String, Object> requestLink(String email) {
        ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                "/auth/request-link", HttpMethod.POST,
                new HttpEntity<>(Map.of("email", email)),
                MAP_BODY);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    private ResponseEntity<Map<String, Object>> redeemRaw(String token) {
        return restTemplate.exchange(
                "/auth/redeem", HttpMethod.POST,
                new HttpEntity<>(Map.of("token", token)),
                MAP_BODY);
    }

    // a mapped, non-permitAll endpoint: only the security layer can turn this into a 401
    private ResponseEntity<Map<String, Object>> protectedRequest(String authorizationHeader) {
        HttpHeaders headers = new HttpHeaders();
        if (authorizationHeader != null) {
            headers.set(HttpHeaders.AUTHORIZATION, authorizationHeader);
        }
        return restTemplate.exchange(
                "/internal/probe", HttpMethod.GET,
                new HttpEntity<Void>(null, headers),
                MAP_BODY);
    }

    private static String uniqueEmail() {
        return "auth-test+" + UUID.randomUUID() + "@example.com";
    }
}
