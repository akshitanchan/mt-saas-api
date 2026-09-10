package com.akshitanchan.saas.rbac;

import static org.assertj.core.api.Assertions.assertThat;

import com.akshitanchan.saas.OrgScopedTestSupport;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

// resolution order (org missing -> not a member -> role lacks the action), cross-tenant
// rejection, and at least one positive case per row of the permission matrix
class RbacResolutionOrderTest extends OrgScopedTestSupport {

    @Test
    void missingOrgReturns404BeforeMembershipIsChecked() {
        String jwt = login(uniqueEmail("owner"));

        ResponseEntity<Map<String, Object>> response = get("/orgs/" + UUID.randomUUID() + "/projects", jwt);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).isEqualTo(Map.of("detail", "org not found"));
    }

    @Test
    void nonMemberGets403BeforeRoleIsChecked() {
        String ownerJwt = login(uniqueEmail("owner"));
        String orgId = createOrg(ownerJwt, "acme");
        String outsiderJwt = login(uniqueEmail("outsider"));

        ResponseEntity<Map<String, Object>> response = get("/orgs/" + orgId + "/projects", outsiderJwt);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody()).isEqualTo(Map.of("detail", "not a member of this org"));
    }

    @Test
    void roleWithoutPermissionReturns403Forbidden() {
        String ownerJwt = login(uniqueEmail("owner"));
        String orgId = createOrg(ownerJwt, "acme");
        String memberJwt = inviteAndLogin(ownerJwt, orgId, "member");

        ResponseEntity<Map<String, Object>> response = post("/orgs/" + orgId + "/projects", memberJwt, Map.of("name", "blocked"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody()).isEqualTo(Map.of("detail", "forbidden"));
    }

    @Test
    void crossTenantAccessIsRejected() {
        String ownerJwt = login(uniqueEmail("owner"));
        String orgId = createOrg(ownerJwt, "acme");
        // this user is authenticated but never a member of anyone else's org
        String helperJwt = login(uniqueEmail("helper"));

        ResponseEntity<Map<String, Object>> response = get("/orgs/" + orgId + "/projects", helperJwt);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody()).isEqualTo(Map.of("detail", "not a member of this org"));
    }

    @Test
    void memberCanViewTheOrgTheyBelongTo() {
        String ownerJwt = login(uniqueEmail("owner"));
        String orgId = createOrg(ownerJwt, "acme");
        String memberJwt = inviteAndLogin(ownerJwt, orgId, "member");

        ResponseEntity<Map<String, Object>> response = get("/orgs/" + orgId, memberJwt);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void memberCanReadProjectsInTheirOrg() {
        String ownerJwt = login(uniqueEmail("owner"));
        String orgId = createOrg(ownerJwt, "acme");
        createProject(ownerJwt, orgId, "proj");
        String memberJwt = inviteAndLogin(ownerJwt, orgId, "member");

        ResponseEntity<String> response = getStatus("/orgs/" + orgId + "/projects", memberJwt);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void adminCanUpdateAProject() {
        String ownerJwt = login(uniqueEmail("owner"));
        String orgId = createOrg(ownerJwt, "acme");
        String projectId = createProject(ownerJwt, orgId, "proj");
        String adminJwt = inviteAndLogin(ownerJwt, orgId, "admin");

        ResponseEntity<Map<String, Object>> response = patch(
                "/orgs/" + orgId + "/projects/" + projectId, adminJwt, Map.of("name", "renamed-by-admin"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().get("name")).isEqualTo("renamed-by-admin");
    }

    @Test
    void adminCanDeleteAProject() {
        String ownerJwt = login(uniqueEmail("owner"));
        String orgId = createOrg(ownerJwt, "acme");
        String projectId = createProject(ownerJwt, orgId, "proj");
        String adminJwt = inviteAndLogin(ownerJwt, orgId, "admin");

        ResponseEntity<Map<String, Object>> response = delete("/orgs/" + orgId + "/projects/" + projectId, adminJwt);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo(Map.of("deleted", true));
    }

    @Test
    void memberCanCreateATask() {
        String ownerJwt = login(uniqueEmail("owner"));
        String orgId = createOrg(ownerJwt, "acme");
        String projectId = createProject(ownerJwt, orgId, "proj");
        String memberJwt = inviteAndLogin(ownerJwt, orgId, "member");

        Map<String, Object> response = createTask(memberJwt, orgId, projectId, Map.of("title", "member-made"));

        assertThat(response.get("title")).isEqualTo("member-made");
    }

    @Test
    void memberCanReadTasks() {
        String ownerJwt = login(uniqueEmail("owner"));
        String orgId = createOrg(ownerJwt, "acme");
        String projectId = createProject(ownerJwt, orgId, "proj");
        createTask(ownerJwt, orgId, projectId, Map.of("title", "t"));
        String memberJwt = inviteAndLogin(ownerJwt, orgId, "member");

        ResponseEntity<String> response = getStatus("/orgs/" + orgId + "/projects/" + projectId + "/tasks", memberJwt);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void ownerCanDeleteATask() {
        String ownerJwt = login(uniqueEmail("owner"));
        String orgId = createOrg(ownerJwt, "acme");
        String projectId = createProject(ownerJwt, orgId, "proj");
        String taskId = (String) createTask(ownerJwt, orgId, projectId, Map.of("title", "t")).get("id");

        ResponseEntity<Map<String, Object>> response = delete("/orgs/" + orgId + "/tasks/" + taskId, ownerJwt);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo(Map.of("deleted", true));
    }

    @Test
    void memberCannotDeleteATask() {
        String ownerJwt = login(uniqueEmail("owner"));
        String orgId = createOrg(ownerJwt, "acme");
        String projectId = createProject(ownerJwt, orgId, "proj");
        String taskId = (String) createTask(ownerJwt, orgId, projectId, Map.of("title", "t")).get("id");
        String memberJwt = inviteAndLogin(ownerJwt, orgId, "member");

        ResponseEntity<Map<String, Object>> response = delete("/orgs/" + orgId + "/tasks/" + taskId, memberJwt);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody()).isEqualTo(Map.of("detail", "forbidden"));
    }
}
