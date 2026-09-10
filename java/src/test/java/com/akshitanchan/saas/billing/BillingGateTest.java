package com.akshitanchan.saas.billing;

import static org.assertj.core.api.Assertions.assertThat;

import com.akshitanchan.saas.OrgScopedTestSupport;
import com.akshitanchan.saas.auth.User;
import com.akshitanchan.saas.auth.UserRepository;
import com.akshitanchan.saas.orgs.Org;
import com.akshitanchan.saas.orgs.OrgRepository;
import com.akshitanchan.saas.orgs.SubscriptionStatus;
import com.akshitanchan.saas.tasks.Task;
import com.akshitanchan.saas.tasks.TaskRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

class BillingGateTest extends OrgScopedTestSupport {

    @Autowired
    private OrgRepository orgRepository;

    @Autowired
    private TaskRepository taskRepository;

    @Autowired
    private UserRepository userRepository;

    @Test
    void freePlanProjectLimitBlocksTheFourthCreate() {
        String jwt = login(uniqueEmail("owner"));
        String orgId = createOrg(jwt, "acme");

        for (int i = 0; i < 3; i++) {
            ResponseEntity<Map<String, Object>> response = post("/orgs/" + orgId + "/projects", jwt, Map.of("name", "p" + i));
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        }

        ResponseEntity<Map<String, Object>> blocked = post("/orgs/" + orgId + "/projects", jwt, Map.of("name", "p3"));
        assertThat(blocked.getStatusCode()).isEqualTo(HttpStatus.PAYMENT_REQUIRED);
        assertThat(blocked.getBody()).isEqualTo(Map.of("detail", "free_plan_project_limit"));
    }

    @Test
    void freePlanMemberLimitBlocksTheFifthMembership() {
        String jwt = login(uniqueEmail("owner"));
        String orgId = createOrg(jwt, "acme");

        // the owner is membership #1, so 3 more invites reach the cap of 4
        for (int i = 0; i < 3; i++) {
            invite(jwt, orgId, uniqueEmail("member" + i), "member");
        }

        ResponseEntity<Map<String, Object>> blocked = post(
                "/orgs/" + orgId + "/invites", jwt, Map.of("email", uniqueEmail("overflow"), "role", "member"));
        assertThat(blocked.getStatusCode()).isEqualTo(HttpStatus.PAYMENT_REQUIRED);
        assertThat(blocked.getBody()).isEqualTo(Map.of("detail", "free_plan_member_limit"));
    }

    @Test
    void freePlanTaskLimitBlocksThe101stCreate() {
        String email = uniqueEmail("owner");
        String jwt = login(email);
        String orgId = createOrg(jwt, "acme");
        String projectId = createProject(jwt, orgId, "proj");
        UUID ownerId = userRepository.findByEmail(email).orElseThrow().getId();

        // seed 100 tasks directly: driving this through 100 http round trips would only
        // slow the suite down without exercising anything the repository insert doesn't
        List<Task> tasks = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            tasks.add(new Task(UUID.fromString(orgId), UUID.fromString(projectId), "t" + i, ownerId));
        }
        taskRepository.saveAll(tasks);

        ResponseEntity<Map<String, Object>> blocked = post(
                "/orgs/" + orgId + "/projects/" + projectId + "/tasks", jwt, Map.of("title", "t100"));
        assertThat(blocked.getStatusCode()).isEqualTo(HttpStatus.PAYMENT_REQUIRED);
        assertThat(blocked.getBody()).isEqualTo(Map.of("detail", "free_plan_task_limit"));
    }

    @Test
    void billingBlockStopsWritesAcrossInvitesProjectsAndTasks() {
        String jwt = login(uniqueEmail("owner"));
        String orgId = createOrg(jwt, "acme");
        String projectId = createProject(jwt, orgId, "pre-existing");
        String taskId = (String) createTask(jwt, orgId, projectId, Map.of("title", "pre-existing")).get("id");

        blockBilling(orgId);

        ResponseEntity<Map<String, Object>> inviteBlocked = post(
                "/orgs/" + orgId + "/invites", jwt, Map.of("email", uniqueEmail("blocked"), "role", "member"));
        assertBillingRequired(inviteBlocked);

        ResponseEntity<Map<String, Object>> projectCreateBlocked = post("/orgs/" + orgId + "/projects", jwt, Map.of("name", "x"));
        assertBillingRequired(projectCreateBlocked);

        ResponseEntity<Map<String, Object>> projectUpdateBlocked = patch(
                "/orgs/" + orgId + "/projects/" + projectId, jwt, Map.of("name", "x"));
        assertBillingRequired(projectUpdateBlocked);

        ResponseEntity<Map<String, Object>> projectDeleteBlocked = delete("/orgs/" + orgId + "/projects/" + projectId, jwt);
        assertBillingRequired(projectDeleteBlocked);

        ResponseEntity<Map<String, Object>> taskCreateBlocked = post(
                "/orgs/" + orgId + "/projects/" + projectId + "/tasks", jwt, Map.of("title", "x"));
        assertBillingRequired(taskCreateBlocked);

        ResponseEntity<Map<String, Object>> taskUpdateBlocked = patch("/orgs/" + orgId + "/tasks/" + taskId, jwt, Map.of("title", "x"));
        assertBillingRequired(taskUpdateBlocked);

        ResponseEntity<Map<String, Object>> taskDeleteBlocked = delete("/orgs/" + orgId + "/tasks/" + taskId, jwt);
        assertBillingRequired(taskDeleteBlocked);
    }

    @Test
    void billingBlockDoesNotStopReads() {
        String jwt = login(uniqueEmail("owner"));
        String orgId = createOrg(jwt, "acme");
        String projectId = createProject(jwt, orgId, "pre-existing");
        createTask(jwt, orgId, projectId, Map.of("title", "pre-existing"));

        blockBilling(orgId);

        ResponseEntity<String> projects = getStatus("/orgs/" + orgId + "/projects", jwt);
        assertThat(projects.getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<String> tasks = getStatus("/orgs/" + orgId + "/projects/" + projectId + "/tasks", jwt);
        assertThat(tasks.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    // sets the subscription status directly through the repository, bypassing the stripe
    // webhook flow that normally drives it
    private void blockBilling(String orgId) {
        Org org = orgRepository.findById(UUID.fromString(orgId)).orElseThrow();
        org.setSubscriptionStatus(SubscriptionStatus.past_due);
        orgRepository.save(org);
    }

    private static void assertBillingRequired(ResponseEntity<Map<String, Object>> response) {
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.PAYMENT_REQUIRED);
        assertThat(response.getBody()).isEqualTo(Map.of("detail", "billing_required"));
    }
}
