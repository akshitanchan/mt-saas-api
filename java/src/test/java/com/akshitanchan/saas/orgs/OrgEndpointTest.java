package com.akshitanchan.saas.orgs;

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

class OrgEndpointTest extends OrgScopedTestSupport {

    private static final ParameterizedTypeReference<List<Map<String, Object>>> LIST_BODY = new ParameterizedTypeReference<>() {
    };

    @Test
    void createOrgReturnsIdAndNameAndMakesTheCallerOwner() {
        String jwt = login(uniqueEmail("owner"));

        ResponseEntity<Map<String, Object>> response = post("/orgs", jwt, Map.of("name", "acme"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().keySet()).containsExactlyInAnyOrder("id", "name");
        assertThat(response.getBody().get("name")).isEqualTo("acme");
        UUID.fromString((String) response.getBody().get("id"));
    }

    @Test
    void orgListIsNewestFirst() {
        String jwt = login(uniqueEmail("owner"));
        String olderId = createOrg(jwt, "org-older");
        String newerId = createOrg(jwt, "org-newer");

        ResponseEntity<List<Map<String, Object>>> response = restTemplate.exchange(
                "/orgs", HttpMethod.GET, new HttpEntity<Void>(null, authHeaders(jwt)), LIST_BODY);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);

        List<Map<String, Object>> orgs = response.getBody();
        assertThat(indexOfId(orgs, newerId)).isLessThan(indexOfId(orgs, olderId));
    }

    @Test
    void getOrgByIdReturnsIdAndName() {
        String jwt = login(uniqueEmail("owner"));
        String orgId = createOrg(jwt, "acme");

        ResponseEntity<Map<String, Object>> response = get("/orgs/" + orgId, jwt);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo(Map.of("id", orgId, "name", "acme"));
    }

    @Test
    void unknownOrgIdReturns404() {
        String jwt = login(uniqueEmail("owner"));

        ResponseEntity<Map<String, Object>> response = get("/orgs/" + UUID.randomUUID(), jwt);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).isEqualTo(Map.of("detail", "org not found"));
    }

    @Test
    void ownerCanInviteBothAdminAndMember() {
        String jwt = login(uniqueEmail("owner"));
        String orgId = createOrg(jwt, "acme");

        Map<String, Object> adminInvite = invite(jwt, orgId, uniqueEmail("invitee"), "admin");
        assertThat(adminInvite.keySet()).containsExactlyInAnyOrder("user_id", "org_id", "role");
        assertThat(adminInvite.get("role")).isEqualTo("admin");

        Map<String, Object> memberInvite = invite(jwt, orgId, uniqueEmail("invitee"), "member");
        assertThat(memberInvite.get("role")).isEqualTo("member");
    }

    @Test
    void adminCanInviteMemberButNotAdmin() {
        String ownerJwt = login(uniqueEmail("owner"));
        String orgId = createOrg(ownerJwt, "acme");
        String adminJwt = inviteAndLogin(ownerJwt, orgId, "admin");

        Map<String, Object> memberInvite = invite(adminJwt, orgId, uniqueEmail("invitee"), "member");
        assertThat(memberInvite.get("role")).isEqualTo("member");

        ResponseEntity<Map<String, Object>> blocked = post(
                "/orgs/" + orgId + "/invites", adminJwt, Map.of("email", uniqueEmail("invitee"), "role", "admin"));
        assertThat(blocked.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(blocked.getBody()).isEqualTo(Map.of("detail", "forbidden"));
    }

    @Test
    void memberCannotInviteAnyone() {
        String ownerJwt = login(uniqueEmail("owner"));
        String orgId = createOrg(ownerJwt, "acme");
        String memberJwt = inviteAndLogin(ownerJwt, orgId, "member");

        ResponseEntity<Map<String, Object>> response = post(
                "/orgs/" + orgId + "/invites", memberJwt, Map.of("email", uniqueEmail("invitee"), "role", "member"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody()).isEqualTo(Map.of("detail", "forbidden"));
    }

    @Test
    void invitingAnExistingMemberReturnsTheirMembershipUnchanged() {
        String jwt = login(uniqueEmail("owner"));
        String orgId = createOrg(jwt, "acme");
        String email = uniqueEmail("invitee");

        Map<String, Object> first = invite(jwt, orgId, email, "member");
        Object userId = first.get("user_id");

        // role is ignored on the second invite: the existing membership wins
        Map<String, Object> second = invite(jwt, orgId, email, "admin");
        assertThat(second).isEqualTo(Map.of("user_id", userId, "org_id", orgId, "role", "member"));
    }
}
