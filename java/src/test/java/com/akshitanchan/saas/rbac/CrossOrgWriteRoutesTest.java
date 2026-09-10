package com.akshitanchan.saas.rbac;

import static org.assertj.core.api.Assertions.assertThat;

import com.akshitanchan.saas.OrgScopedTestSupport;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

// a member of one org, authenticated and otherwise privileged in their own org, is still just
// an outsider against another org's routes: every write is rejected, and by-id lookups (patch,
// delete) never leak whether the row exists, since membership is checked before it's fetched
class CrossOrgWriteRoutesTest extends OrgScopedTestSupport {

    @Test
    void ownerOfAnotherOrgIsDeniedOnEveryWriteRouteOfThisOrg() {
        String ownerAJwt = login(uniqueEmail("owner-a"));
        String orgA = createOrg(ownerAJwt, "org-a");
        String projectA = createProject(ownerAJwt, orgA, "p-a");
        String taskA = (String) createTask(ownerAJwt, orgA, projectA, Map.of("title", "t-a")).get("id");

        String ownerBJwt = login(uniqueEmail("owner-b"));
        createOrg(ownerBJwt, "org-b");

        assertForbidden(post("/orgs/" + orgA + "/invites", ownerBJwt, Map.of("email", uniqueEmail("x"), "role", "member")));
        assertForbidden(post("/orgs/" + orgA + "/projects", ownerBJwt, Map.of("name", "nope")));
        assertForbidden(patch("/orgs/" + orgA + "/projects/" + projectA, ownerBJwt, Map.of("name", "nope")));
        assertForbidden(delete("/orgs/" + orgA + "/projects/" + projectA, ownerBJwt));
        assertForbidden(post("/orgs/" + orgA + "/projects/" + projectA + "/tasks", ownerBJwt, Map.of("title", "nope")));
        assertForbidden(patch("/orgs/" + orgA + "/tasks/" + taskA, ownerBJwt, Map.of("title", "nope")));
        assertForbidden(delete("/orgs/" + orgA + "/tasks/" + taskA, ownerBJwt));
    }

    @Test
    void ownerOfAnotherOrgIsDeniedReadingThisOrgsProjectsAndTasks() {
        String ownerAJwt = login(uniqueEmail("owner-a"));
        String orgA = createOrg(ownerAJwt, "org-a");
        String projectA = createProject(ownerAJwt, orgA, "p-a");
        createTask(ownerAJwt, orgA, projectA, Map.of("title", "t-a"));

        String ownerBJwt = login(uniqueEmail("owner-b"));
        createOrg(ownerBJwt, "org-b");

        ResponseEntity<String> projects = getStatus("/orgs/" + orgA + "/projects", ownerBJwt);
        assertThat(projects.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        ResponseEntity<String> tasks = getStatus("/orgs/" + orgA + "/projects/" + projectA + "/tasks", ownerBJwt);
        assertThat(tasks.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    // a genuine cross-tenant case: the caller is a real member elsewhere, not just an
    // unaffiliated account, and still can't reach org a's project by id
    @Test
    void memberOfOneOrgCannotUpdateAnotherOrgsProjectById() {
        String ownerAJwt = login(uniqueEmail("owner-a"));
        String orgA = createOrg(ownerAJwt, "org-a");
        String projectA = createProject(ownerAJwt, orgA, "p-a");

        String ownerBJwt = login(uniqueEmail("owner-b"));
        String orgB = createOrg(ownerBJwt, "org-b");
        String memberOfBJwt = inviteAndLogin(ownerBJwt, orgB, "member");

        ResponseEntity<Map<String, Object>> response = patch(
                "/orgs/" + orgA + "/projects/" + projectA, memberOfBJwt, Map.of("name", "hacked"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody()).isEqualTo(Map.of("detail", "not a member of this org"));
    }

    @Test
    void memberOfOneOrgCannotUpdateAnotherOrgsTaskById() {
        String ownerAJwt = login(uniqueEmail("owner-a"));
        String orgA = createOrg(ownerAJwt, "org-a");
        String projectA = createProject(ownerAJwt, orgA, "p-a");
        String taskA = (String) createTask(ownerAJwt, orgA, projectA, Map.of("title", "t-a")).get("id");

        String ownerBJwt = login(uniqueEmail("owner-b"));
        String orgB = createOrg(ownerBJwt, "org-b");
        String memberOfBJwt = inviteAndLogin(ownerBJwt, orgB, "member");

        ResponseEntity<Map<String, Object>> response = patch(
                "/orgs/" + orgA + "/tasks/" + taskA, memberOfBJwt, Map.of("title", "hacked"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody()).isEqualTo(Map.of("detail", "not a member of this org"));
    }

    private static void assertForbidden(ResponseEntity<Map<String, Object>> response) {
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }
}
