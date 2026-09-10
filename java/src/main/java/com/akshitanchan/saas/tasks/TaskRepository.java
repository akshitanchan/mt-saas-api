package com.akshitanchan.saas.tasks;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TaskRepository extends JpaRepository<Task, UUID> {

    List<Task> findAllByOrgIdAndProjectIdOrderByCreatedAtDesc(UUID orgId, UUID projectId);

    Optional<Task> findByIdAndOrgId(UUID id, UUID orgId);

    long countByOrgId(UUID orgId);
}
