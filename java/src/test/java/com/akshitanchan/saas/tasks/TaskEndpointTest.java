package com.akshitanchan.saas.tasks;

import static org.assertj.core.api.Assertions.assertThat;

import com.akshitanchan.saas.OrgScopedTestSupport;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

class TaskEndpointTest extends OrgScopedTestSupport {

    private static final ParameterizedTypeReference<List<Map<String, Object>>> LIST_BODY = new ParameterizedTypeReference<>() {
    };

    @Test
    void taskCrudHappyPath() {
        String jwt = login(uniqueEmail("owner"));
        String orgId = createOrg(jwt, "acme");
        String projectId = createProject(jwt, orgId, "proj");

        Map<String, Object> created = createTask(jwt, orgId, projectId, Map.of("title", "t1"));
        assertThat(created.keySet()).containsExactlyInAnyOrder(
                "id", "org_id", "project_id", "title", "status", "created_by", "assigned_to");
        assertThat(created.get("status")).isEqualTo("todo");
        assertThat(created.get("assigned_to")).isNull();
        String taskId = (String) created.get("id");

        ResponseEntity<Map<String, Object>> updated = patch(
                "/orgs/" + orgId + "/tasks/" + taskId, jwt, Map.of("title", "t1-renamed", "status", "doing"));
        assertThat(updated.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(updated.getBody().get("title")).isEqualTo("t1-renamed");
        assertThat(updated.getBody().get("status")).isEqualTo("doing");

        ResponseEntity<Map<String, Object>> deleted = delete("/orgs/" + orgId + "/tasks/" + taskId, jwt);
        assertThat(deleted.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(deleted.getBody()).isEqualTo(Map.of("deleted", true));

        ResponseEntity<Map<String, Object>> afterDelete = patch(
                "/orgs/" + orgId + "/tasks/" + taskId, jwt, Map.of("title", "gone"));
        assertThat(afterDelete.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(afterDelete.getBody()).isEqualTo(Map.of("detail", "task not found"));
    }

    @Test
    void taskListIsNewestFirst() {
        String jwt = login(uniqueEmail("owner"));
        String orgId = createOrg(jwt, "acme");
        String projectId = createProject(jwt, orgId, "proj");
        String olderId = (String) createTask(jwt, orgId, projectId, Map.of("title", "older")).get("id");
        String newerId = (String) createTask(jwt, orgId, projectId, Map.of("title", "newer")).get("id");

        ResponseEntity<List<Map<String, Object>>> response = restTemplate.exchange(
                "/orgs/" + orgId + "/projects/" + projectId + "/tasks", HttpMethod.GET,
                new HttpEntity<Void>(null, authHeaders(jwt)), LIST_BODY);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);

        List<Map<String, Object>> tasks = response.getBody();
        assertThat(indexOfId(tasks, newerId)).isLessThan(indexOfId(tasks, olderId));
    }

    @Test
    void unknownProjectReturns404ForTaskCreation() {
        String jwt = login(uniqueEmail("owner"));
        String orgId = createOrg(jwt, "acme");

        ResponseEntity<Map<String, Object>> response = post(
                "/orgs/" + orgId + "/projects/" + UUID.randomUUID() + "/tasks", jwt, Map.of("title", "x"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).isEqualTo(Map.of("detail", "project not found"));
    }

    @Test
    void taskPartialUpdateRespectsAbsentVsExplicitNullAssignedTo() {
        String jwt = login(uniqueEmail("owner"));
        String orgId = createOrg(jwt, "acme");
        String projectId = createProject(jwt, orgId, "proj");
        String taskId = (String) createTask(jwt, orgId, projectId, Map.of("title", "t")).get("id");

        Map<String, Object> invited = invite(jwt, orgId, uniqueEmail("assignee"), "member");
        String assigneeId = (String) invited.get("user_id");

        ResponseEntity<Map<String, Object>> assigned = patch(
                "/orgs/" + orgId + "/tasks/" + taskId, jwt, mapOfNullable("assigned_to", assigneeId));
        assertThat(assigned.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(assigned.getBody().get("assigned_to")).isEqualTo(assigneeId);

        // assigned_to absent from the body leaves the existing assignment untouched
        ResponseEntity<Map<String, Object>> untouched = patch(
                "/orgs/" + orgId + "/tasks/" + taskId, jwt, Map.of("title", "still assigned"));
        assertThat(untouched.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(untouched.getBody().get("assigned_to")).isEqualTo(assigneeId);

        // assigned_to explicitly null unassigns
        ResponseEntity<Map<String, Object>> unassigned = patch(
                "/orgs/" + orgId + "/tasks/" + taskId, jwt, mapOfNullable("assigned_to", null));
        assertThat(unassigned.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(unassigned.getBody().get("assigned_to")).isNull();
    }

    @Test
    void memberCanUpdateATaskTheyCreated() {
        String ownerJwt = login(uniqueEmail("owner"));
        String orgId = createOrg(ownerJwt, "acme");
        String projectId = createProject(ownerJwt, orgId, "proj");
        String memberJwt = inviteAndLogin(ownerJwt, orgId, "member");

        Map<String, Object> created = createTask(memberJwt, orgId, projectId, Map.of("title", "mine"));
        String taskId = (String) created.get("id");

        ResponseEntity<Map<String, Object>> response = patch(
                "/orgs/" + orgId + "/tasks/" + taskId, memberJwt, Map.of("status", "doing"));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().get("status")).isEqualTo("doing");
    }

    @Test
    void memberCanUpdateATaskAssignedToThem() {
        String ownerJwt = login(uniqueEmail("owner"));
        String orgId = createOrg(ownerJwt, "acme");
        String projectId = createProject(ownerJwt, orgId, "proj");

        String memberEmail = uniqueEmail("member");
        Map<String, Object> invited = invite(ownerJwt, orgId, memberEmail, "member");
        String memberUserId = (String) invited.get("user_id");
        String memberJwt = login(memberEmail);

        Map<String, Object> created = createTask(
                ownerJwt, orgId, projectId, Map.of("title", "assigned-to-member", "assigned_to", memberUserId));
        String taskId = (String) created.get("id");

        ResponseEntity<Map<String, Object>> response = patch(
                "/orgs/" + orgId + "/tasks/" + taskId, memberJwt, Map.of("status", "done"));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().get("status")).isEqualTo("done");
    }

    @Test
    void memberCannotUpdateATaskTheyDoNotOwnOrAreNotAssigned() {
        String ownerJwt = login(uniqueEmail("owner"));
        String orgId = createOrg(ownerJwt, "acme");
        String projectId = createProject(ownerJwt, orgId, "proj");
        String memberJwt = inviteAndLogin(ownerJwt, orgId, "member");

        Map<String, Object> created = createTask(ownerJwt, orgId, projectId, Map.of("title", "owners-task"));
        String taskId = (String) created.get("id");

        ResponseEntity<Map<String, Object>> response = patch(
                "/orgs/" + orgId + "/tasks/" + taskId, memberJwt, Map.of("status", "done"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody()).isEqualTo(Map.of("detail", "forbidden"));
    }
}
