package com.akshitanchan.saas.projects;

import static org.assertj.core.api.Assertions.assertThat;

import com.akshitanchan.saas.OrgScopedTestSupport;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

class ProjectEndpointTest extends OrgScopedTestSupport {

    private static final ParameterizedTypeReference<List<Map<String, Object>>> LIST_BODY = new ParameterizedTypeReference<>() {
    };

    @Test
    void projectCrudHappyPath() {
        String jwt = login(uniqueEmail("owner"));
        String orgId = createOrg(jwt, "acme");

        ResponseEntity<Map<String, Object>> created = post("/orgs/" + orgId + "/projects", jwt, Map.of("name", "proj-a"));
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(created.getBody().keySet()).containsExactlyInAnyOrder("id", "org_id", "name");
        String projectId = (String) created.getBody().get("id");

        ResponseEntity<Map<String, Object>> renamed = patch(
                "/orgs/" + orgId + "/projects/" + projectId, jwt, Map.of("name", "proj-a-renamed"));
        assertThat(renamed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(renamed.getBody().get("name")).isEqualTo("proj-a-renamed");

        ResponseEntity<Map<String, Object>> deleted = delete("/orgs/" + orgId + "/projects/" + projectId, jwt);
        assertThat(deleted.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(deleted.getBody()).isEqualTo(Map.of("deleted", true));

        ResponseEntity<Map<String, Object>> afterDelete = patch(
                "/orgs/" + orgId + "/projects/" + projectId, jwt, Map.of("name", "gone"));
        assertThat(afterDelete.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(afterDelete.getBody()).isEqualTo(Map.of("detail", "project not found"));
    }

    @Test
    void projectListIsNewestFirst() {
        String jwt = login(uniqueEmail("owner"));
        String orgId = createOrg(jwt, "acme");
        String olderId = createProject(jwt, orgId, "older");
        String newerId = createProject(jwt, orgId, "newer");

        ResponseEntity<List<Map<String, Object>>> response = restTemplate.exchange(
                "/orgs/" + orgId + "/projects", HttpMethod.GET, new HttpEntity<Void>(null, authHeaders(jwt)), LIST_BODY);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);

        List<Map<String, Object>> projects = response.getBody();
        assertThat(indexOfId(projects, newerId)).isLessThan(indexOfId(projects, olderId));
    }

    @Test
    void unknownProjectIdReturns404OnUpdateAndDelete() {
        String jwt = login(uniqueEmail("owner"));
        String orgId = createOrg(jwt, "acme");
        String unknownProjectId = UUID.randomUUID().toString();

        ResponseEntity<Map<String, Object>> patched = patch(
                "/orgs/" + orgId + "/projects/" + unknownProjectId, jwt, Map.of("name", "x"));
        assertThat(patched.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(patched.getBody()).isEqualTo(Map.of("detail", "project not found"));

        ResponseEntity<Map<String, Object>> deleted = delete("/orgs/" + orgId + "/projects/" + unknownProjectId, jwt);
        assertThat(deleted.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(deleted.getBody()).isEqualTo(Map.of("detail", "project not found"));
    }

    @Test
    void memberCannotDeleteAProject() {
        String ownerJwt = login(uniqueEmail("owner"));
        String orgId = createOrg(ownerJwt, "acme");
        String projectId = createProject(ownerJwt, orgId, "proj");
        String memberJwt = inviteAndLogin(ownerJwt, orgId, "member");

        ResponseEntity<Map<String, Object>> response = delete("/orgs/" + orgId + "/projects/" + projectId, memberJwt);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody()).isEqualTo(Map.of("detail", "forbidden"));
    }

    private static int indexOfId(List<Map<String, Object>> items, String id) {
        for (int i = 0; i < items.size(); i++) {
            if (id.equals(items.get(i).get("id"))) {
                return i;
            }
        }
        throw new AssertionError("id " + id + " not found in " + items);
    }

    private HttpHeaders authHeaders(String jwt) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.AUTHORIZATION, "Bearer " + jwt);
        return headers;
    }
}
