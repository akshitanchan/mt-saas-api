package com.akshitanchan.saas.billing;

import com.akshitanchan.saas.orgs.Org;
import com.akshitanchan.saas.orgs.Plan;
import com.akshitanchan.saas.orgs.SubscriptionStatus;
import com.akshitanchan.saas.projects.ProjectRepository;
import com.akshitanchan.saas.rbac.MembershipRepository;
import com.akshitanchan.saas.tasks.TaskRepository;
import com.akshitanchan.saas.web.ApiException;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

// mirrors app/billing/gates.py: a blocked subscription status stops every write, and the
// free plan caps creates once an org already has this many rows of that kind
@Component
public class BillingGate {

    public static final int FREE_PROJECT_LIMIT = 3;
    public static final int FREE_TASK_LIMIT = 100;
    public static final int FREE_MEMBER_LIMIT = 4;

    private static final Set<SubscriptionStatus> BLOCKED_STATUSES =
            EnumSet.of(SubscriptionStatus.past_due, SubscriptionStatus.canceled, SubscriptionStatus.unpaid);

    private final ProjectRepository projectRepository;
    private final TaskRepository taskRepository;
    private final MembershipRepository membershipRepository;

    public BillingGate(
            ProjectRepository projectRepository,
            TaskRepository taskRepository,
            MembershipRepository membershipRepository) {
        this.projectRepository = projectRepository;
        this.taskRepository = taskRepository;
        this.membershipRepository = membershipRepository;
    }

    public void requireWritable(Org org) {
        if (BLOCKED_STATUSES.contains(org.getSubscriptionStatus())) {
            throw new ApiException(HttpStatus.PAYMENT_REQUIRED, "billing_required");
        }
    }

    public void requireProjectCapacity(Org org, UUID orgId) {
        if (org.getPlan() != Plan.free) {
            return;
        }
        if (projectRepository.countByOrgId(orgId) >= FREE_PROJECT_LIMIT) {
            throw new ApiException(HttpStatus.PAYMENT_REQUIRED, "free_plan_project_limit");
        }
    }

    public void requireTaskCapacity(Org org, UUID orgId) {
        if (org.getPlan() != Plan.free) {
            return;
        }
        if (taskRepository.countByOrgId(orgId) >= FREE_TASK_LIMIT) {
            throw new ApiException(HttpStatus.PAYMENT_REQUIRED, "free_plan_task_limit");
        }
    }

    public void requireMemberCapacity(Org org, UUID orgId) {
        if (org.getPlan() != Plan.free) {
            return;
        }
        if (membershipRepository.countByOrgId(orgId) >= FREE_MEMBER_LIMIT) {
            throw new ApiException(HttpStatus.PAYMENT_REQUIRED, "free_plan_member_limit");
        }
    }
}
