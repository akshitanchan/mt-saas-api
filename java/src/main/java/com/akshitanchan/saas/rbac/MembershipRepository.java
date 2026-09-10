package com.akshitanchan.saas.rbac;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MembershipRepository extends JpaRepository<Membership, MembershipId> {

    @Query("select count(m) from Membership m where m.id.orgId = :orgId")
    long countByOrgId(@Param("orgId") UUID orgId);
}
