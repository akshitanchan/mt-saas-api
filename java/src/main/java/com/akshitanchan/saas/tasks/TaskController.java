package com.akshitanchan.saas.tasks;

import com.akshitanchan.saas.auth.User;
import com.akshitanchan.saas.billing.BillingGate;
import com.akshitanchan.saas.projects.ProjectRepository;
import com.akshitanchan.saas.rbac.OrgAccess;
import com.akshitanchan.saas.rbac.Role;
import com.akshitanchan.saas.web.ApiException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.openapitools.jackson.nullable.JsonNullable;
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
@RequestMapping("/orgs/{org_id}")
public class TaskController {

    private final TaskRepository taskRepository;
    private final ProjectRepository projectRepository;
    private final OrgAccess orgAccess;
    private final BillingGate billingGate;

    public TaskController(
            TaskRepository taskRepository,
            ProjectRepository projectRepository,
            OrgAccess orgAccess,
            BillingGate billingGate) {
        this.taskRepository = taskRepository;
        this.projectRepository = projectRepository;
        this.orgAccess = orgAccess;
        this.billingGate = billingGate;
    }

    @PostMapping("/projects/{project_id}/tasks")
    @Transactional
    public TaskResponse createTask(
            @PathVariable("org_id") UUID orgId,
            @PathVariable("project_id") UUID projectId,
            @Valid @RequestBody TaskCreateRequest body,
            @AuthenticationPrincipal User user) {
        OrgAccess.Context ctx = orgAccess.require(orgId, user, "tasks:create");
        billingGate.requireWritable(ctx.org());
        billingGate.requireTaskCapacity(ctx.org(), orgId);

        projectRepository.findByIdAndOrgId(projectId, orgId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "project not found"));

        Task task = new Task(orgId, projectId, body.title(), user.getId());
        task.setAssignedTo(body.assignedTo());
        taskRepository.save(task);
        return toResponse(task);
    }

    @GetMapping("/projects/{project_id}/tasks")
    public List<TaskResponse> listTasks(
            @PathVariable("org_id") UUID orgId,
            @PathVariable("project_id") UUID projectId,
            @AuthenticationPrincipal User user) {
        orgAccess.require(orgId, user, "tasks:read");

        projectRepository.findByIdAndOrgId(projectId, orgId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "project not found"));

        return taskRepository.findAllByOrgIdAndProjectIdOrderByCreatedAtDesc(orgId, projectId).stream()
                .map(TaskController::toResponse)
                .toList();
    }

    @PatchMapping("/tasks/{task_id}")
    @Transactional
    public TaskResponse updateTask(
            @PathVariable("org_id") UUID orgId,
            @PathVariable("task_id") UUID taskId,
            @Valid @RequestBody TaskUpdateRequest body,
            @AuthenticationPrincipal User user) {
        OrgAccess.Context ctx = orgAccess.require(orgId, user, "tasks:update");
        billingGate.requireWritable(ctx.org());

        Task task = taskRepository.findByIdAndOrgId(taskId, orgId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "task not found"));

        // members may only edit a task they created or are assigned to; owners and admins
        // are exempt from this check entirely
        if (ctx.membership().getRole() == Role.member
                && !task.getCreatedBy().equals(user.getId())
                && !user.getId().equals(task.getAssignedTo())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "forbidden");
        }

        if (body.title() != null) {
            task.setTitle(body.title());
        }
        if (body.status() != null) {
            task.setStatus(body.status());
        }
        // absent from the request body leaves the assignment untouched; present-and-null unassigns
        if (body.assignedTo().isPresent()) {
            task.setAssignedTo(body.assignedTo().get());
        }

        return toResponse(task);
    }

    @DeleteMapping("/tasks/{task_id}")
    @Transactional
    public Map<String, Boolean> deleteTask(
            @PathVariable("org_id") UUID orgId,
            @PathVariable("task_id") UUID taskId,
            @AuthenticationPrincipal User user) {
        OrgAccess.Context ctx = orgAccess.require(orgId, user, "tasks:delete");
        billingGate.requireWritable(ctx.org());

        Task task = taskRepository.findByIdAndOrgId(taskId, orgId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "task not found"));
        taskRepository.delete(task);
        return Map.of("deleted", true);
    }

    private static TaskResponse toResponse(Task task) {
        return new TaskResponse(
                task.getId(),
                task.getOrgId(),
                task.getProjectId(),
                task.getTitle(),
                task.getStatus(),
                task.getCreatedBy(),
                task.getAssignedTo());
    }

    public record TaskCreateRequest(@NotBlank String title, UUID assignedTo) {
    }

    public record TaskUpdateRequest(String title, TaskStatus status, JsonNullable<UUID> assignedTo) {

        // a missing "assigned_to" key never reaches the JsonNullable deserializer, so the
        // constructor argument arrives as a plain java null; normalize that to "undefined"
        // so callers can tell "absent" apart from an explicit {"assigned_to": null}
        public TaskUpdateRequest {
            if (assignedTo == null) {
                assignedTo = JsonNullable.undefined();
            }
        }
    }

    public record TaskResponse(
            UUID id,
            UUID orgId,
            UUID projectId,
            String title,
            TaskStatus status,
            UUID createdBy,
            UUID assignedTo) {
    }
}
