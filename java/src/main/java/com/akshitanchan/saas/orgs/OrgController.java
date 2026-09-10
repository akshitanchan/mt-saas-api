package com.akshitanchan.saas.orgs;

import com.akshitanchan.saas.auth.User;
import com.akshitanchan.saas.auth.UserRepository;
import com.akshitanchan.saas.billing.BillingGate;
import com.akshitanchan.saas.rbac.Membership;
import com.akshitanchan.saas.rbac.MembershipId;
import com.akshitanchan.saas.rbac.MembershipRepository;
import com.akshitanchan.saas.rbac.OrgAccess;
import com.akshitanchan.saas.rbac.Role;
import com.akshitanchan.saas.web.ApiException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/orgs")
public class OrgController {

    private final OrgRepository orgRepository;
    private final MembershipRepository membershipRepository;
    private final UserRepository userRepository;
    private final OrgAccess orgAccess;
    private final BillingGate billingGate;

    public OrgController(
            OrgRepository orgRepository,
            MembershipRepository membershipRepository,
            UserRepository userRepository,
            OrgAccess orgAccess,
            BillingGate billingGate) {
        this.orgRepository = orgRepository;
        this.membershipRepository = membershipRepository;
        this.userRepository = userRepository;
        this.orgAccess = orgAccess;
        this.billingGate = billingGate;
    }

    @PostMapping
    @Transactional
    public OrgResponse createOrg(@Valid @RequestBody OrgRequest body, @AuthenticationPrincipal User user) {
        Org org = orgRepository.save(new Org(body.name()));
        membershipRepository.save(new Membership(user.getId(), org.getId(), Role.owner));
        return toOrgResponse(org);
    }

    @GetMapping
    public List<OrgResponse> listOrgs(@AuthenticationPrincipal User user) {
        return orgRepository.findAllForMember(user.getId()).stream()
                .map(OrgController::toOrgResponse)
                .toList();
    }

    @GetMapping("/{org_id}")
    public OrgResponse getOrg(@PathVariable("org_id") UUID orgId, @AuthenticationPrincipal User user) {
        OrgAccess.Context ctx = orgAccess.require(orgId, user, "org:view");
        return toOrgResponse(ctx.org());
    }

    @PostMapping("/{org_id}/invites")
    @Transactional
    public MemberResponse invite(
            @PathVariable("org_id") UUID orgId,
            @Valid @RequestBody InviteRequest body,
            @AuthenticationPrincipal User user) {
        OrgAccess.Context ctx = orgAccess.require(orgId, user, "org:invite");
        billingGate.requireWritable(ctx.org());
        billingGate.requireMemberCapacity(ctx.org(), orgId);

        Role requestedRole = body.role() != null ? body.role() : Role.member;
        if (!grantableRoles(ctx.membership().getRole()).contains(requestedRole)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "forbidden");
        }

        String email = body.email().toLowerCase().strip();
        User invited = userRepository.findByEmail(email)
                .orElseGet(() -> userRepository.save(new User(email, null)));

        return membershipRepository.findById(new MembershipId(invited.getId(), orgId))
                .map(OrgController::toMemberResponse)
                .orElseGet(() -> toMemberResponse(
                        membershipRepository.save(new Membership(invited.getId(), orgId, requestedRole))));
    }

    // roles the inviter is allowed to hand out: an owner can mint admin or member, an
    // admin only member; anything else (a member, or an unknown role) grants nothing
    private static Set<Role> grantableRoles(Role inviterRole) {
        return switch (inviterRole) {
            case owner -> EnumSet.of(Role.admin, Role.member);
            case admin -> EnumSet.of(Role.member);
            case member -> EnumSet.noneOf(Role.class);
        };
    }

    private static OrgResponse toOrgResponse(Org org) {
        return new OrgResponse(org.getId(), org.getName());
    }

    private static MemberResponse toMemberResponse(Membership membership) {
        return new MemberResponse(membership.getUserId(), membership.getOrgId(), membership.getRole());
    }

    public record OrgRequest(@NotBlank String name) {
    }

    public record OrgResponse(UUID id, String name) {
    }

    public record InviteRequest(@NotBlank @Email String email, Role role) {
    }

    public record MemberResponse(UUID userId, UUID orgId, Role role) {
    }
}
