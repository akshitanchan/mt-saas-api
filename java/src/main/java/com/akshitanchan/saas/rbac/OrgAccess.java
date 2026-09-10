package com.akshitanchan.saas.rbac;

import com.akshitanchan.saas.auth.User;
import com.akshitanchan.saas.orgs.Org;
import com.akshitanchan.saas.orgs.OrgRepository;
import com.akshitanchan.saas.web.ApiException;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

// the single place every org-scoped controller resolves a request through, in this fixed
// order: org missing -> 404, caller not a member -> 403, role lacks the action -> 403.
// mirrors app/rbac/deps.py's get_org_context + require_perm combined into one call.
@Component
public class OrgAccess {

    private final OrgRepository orgRepository;
    private final MembershipRepository membershipRepository;

    public OrgAccess(OrgRepository orgRepository, MembershipRepository membershipRepository) {
        this.orgRepository = orgRepository;
        this.membershipRepository = membershipRepository;
    }

    public record Context(Org org, Membership membership) {
    }

    public Context require(UUID orgId, User user, String action) {
        Org org = orgRepository.findById(orgId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "org not found"));

        Membership membership = membershipRepository.findById(new MembershipId(user.getId(), orgId))
                .orElseThrow(() -> new ApiException(HttpStatus.FORBIDDEN, "not a member of this org"));

        if (!Perms.allows(action, membership.getRole())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "forbidden");
        }

        return new Context(org, membership);
    }
}
