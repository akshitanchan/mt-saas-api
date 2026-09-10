package com.akshitanchan.saas.projects;

import com.akshitanchan.saas.auth.User;
import com.akshitanchan.saas.billing.BillingGate;
import com.akshitanchan.saas.rbac.OrgAccess;
import com.akshitanchan.saas.web.ApiException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/orgs/{org_id}/projects")
public class ProjectController {

    private final ProjectRepository projectRepository;
    private final OrgAccess orgAccess;
    private final BillingGate billingGate;

    public ProjectController(ProjectRepository projectRepository, OrgAccess orgAccess, BillingGate billingGate) {
        this.projectRepository = projectRepository;
        this.orgAccess = orgAccess;
        this.billingGate = billingGate;
    }

    @PostMapping
    @Transactional
    public ProjectResponse createProject(
            @PathVariable("org_id") UUID orgId,
            @Valid @RequestBody ProjectRequest body,
            @AuthenticationPrincipal User user) {
        OrgAccess.Context ctx = orgAccess.require(orgId, user, "projects:create");
        billingGate.requireWritable(ctx.org());
        billingGate.requireProjectCapacity(ctx.org(), orgId);

        Project project = projectRepository.save(new Project(orgId, body.name()));
        return toResponse(project);
    }

    @GetMapping
    public List<ProjectResponse> listProjects(@PathVariable("org_id") UUID orgId, @AuthenticationPrincipal User user) {
        orgAccess.require(orgId, user, "projects:read");
        return projectRepository.findAllByOrgIdOrderByCreatedAtDesc(orgId).stream()
                .map(ProjectController::toResponse)
                .toList();
    }

    @PatchMapping("/{project_id}")
    @Transactional
    public ProjectResponse updateProject(
            @PathVariable("org_id") UUID orgId,
            @PathVariable("project_id") UUID projectId,
            @Valid @RequestBody ProjectRequest body,
            @AuthenticationPrincipal User user) {
        OrgAccess.Context ctx = orgAccess.require(orgId, user, "projects:update");
        billingGate.requireWritable(ctx.org());

        Project project = projectRepository.findByIdAndOrgId(projectId, orgId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "project not found"));
        project.setName(body.name());
        return toResponse(project);
    }

    @DeleteMapping("/{project_id}")
    @Transactional
    public Map<String, Boolean> deleteProject(
            @PathVariable("org_id") UUID orgId,
            @PathVariable("project_id") UUID projectId,
            @AuthenticationPrincipal User user) {
        OrgAccess.Context ctx = orgAccess.require(orgId, user, "projects:delete");
        billingGate.requireWritable(ctx.org());

        Project project = projectRepository.findByIdAndOrgId(projectId, orgId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "project not found"));
        projectRepository.delete(project);
        return Map.of("deleted", true);
    }

    private static ProjectResponse toResponse(Project project) {
        return new ProjectResponse(project.getId(), project.getOrgId(), project.getName());
    }

    public record ProjectRequest(@NotBlank String name) {
    }

    public record ProjectResponse(UUID id, UUID orgId, String name) {
    }
}
