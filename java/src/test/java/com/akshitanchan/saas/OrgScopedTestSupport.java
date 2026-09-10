package com.akshitanchan.saas;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

// shared http helpers for tests that exercise the org/project/task endpoints: logging a
// fresh user in, creating an org or invitee, and issuing authenticated json requests, all
// in the same style as AuthEndpointTest's private helpers
public abstract class OrgScopedTestSupport extends AbstractIntegrationTest {

    protected static final ParameterizedTypeReference<Map<String, Object>> MAP_BODY = new ParameterizedTypeReference<>() {
    };

    @Autowired
    protected TestRestTemplate restTemplate;

    protected String login(String email) {
        Map<String, Object> requested = post("/auth/request-link", null, Map.of("email", email)).getBody();
        String token = (String) requested.get("token");

        ResponseEntity<Map<String, Object>> redeemed = post("/auth/redeem", null, Map.of("token", token));
        assertThat(redeemed.getStatusCode()).isEqualTo(HttpStatus.OK);
        return (String) redeemed.getBody().get("access_token");
    }

    protected String uniqueEmail(String label) {
        return "org-test-" + label + "-" + UUID.randomUUID() + "@example.com";
    }

    protected String createOrg(String jwt, String name) {
        ResponseEntity<Map<String, Object>> response = post("/orgs", jwt, Map.of("name", name));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return (String) response.getBody().get("id");
    }

    protected Map<String, Object> invite(String jwt, String orgId, String email, String role) {
        ResponseEntity<Map<String, Object>> response = post(
                "/orgs/" + orgId + "/invites", jwt, Map.of("email", email, "role", role));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    // invites a brand new user with the given role and logs them in, in one step
    protected String inviteAndLogin(String inviterJwt, String orgId, String role) {
        String email = uniqueEmail(role);
        invite(inviterJwt, orgId, email, role);
        return login(email);
    }

    protected String createProject(String jwt, String orgId, String name) {
        ResponseEntity<Map<String, Object>> response = post("/orgs/" + orgId + "/projects", jwt, Map.of("name", name));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return (String) response.getBody().get("id");
    }

    protected Map<String, Object> createTask(String jwt, String orgId, String projectId, Map<String, Object> body) {
        ResponseEntity<Map<String, Object>> response = post(
                "/orgs/" + orgId + "/projects/" + projectId + "/tasks", jwt, body);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    protected ResponseEntity<Map<String, Object>> get(String path, String jwt) {
        return restTemplate.exchange(path, HttpMethod.GET, new HttpEntity<Void>(null, headers(jwt)), MAP_BODY);
    }

    // for endpoints that return a json array rather than an object; callers that only
    // need the status code (not a typed body) use this instead of get(...)
    protected ResponseEntity<String> getStatus(String path, String jwt) {
        return restTemplate.exchange(path, HttpMethod.GET, new HttpEntity<Void>(null, headers(jwt)), String.class);
    }

    protected ResponseEntity<Map<String, Object>> post(String path, String jwt, Object body) {
        return restTemplate.exchange(path, HttpMethod.POST, new HttpEntity<>(body, headers(jwt)), MAP_BODY);
    }

    protected ResponseEntity<Map<String, Object>> patch(String path, String jwt, Map<String, Object> body) {
        return restTemplate.exchange(path, HttpMethod.PATCH, new HttpEntity<>(body, headers(jwt)), MAP_BODY);
    }

    protected ResponseEntity<Map<String, Object>> delete(String path, String jwt) {
        return restTemplate.exchange(path, HttpMethod.DELETE, new HttpEntity<Void>(null, headers(jwt)), MAP_BODY);
    }

    // a map that permits the null value Map.of() rejects, for explicit-null request bodies
    protected static Map<String, Object> mapOfNullable(String key, Object value) {
        Map<String, Object> body = new HashMap<>();
        body.put(key, value);
        return body;
    }

    private HttpHeaders headers(String jwt) {
        HttpHeaders headers = new HttpHeaders();
        if (jwt != null) {
            headers.set(HttpHeaders.AUTHORIZATION, "Bearer " + jwt);
        }
        return headers;
    }
}
