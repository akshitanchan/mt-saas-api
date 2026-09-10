package com.akshitanchan.saas.projects;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProjectRepository extends JpaRepository<Project, UUID> {

    List<Project> findAllByOrgIdOrderByCreatedAtDesc(UUID orgId);

    Optional<Project> findByIdAndOrgId(UUID id, UUID orgId);

    long countByOrgId(UUID orgId);
}
