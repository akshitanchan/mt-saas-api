package com.akshitanchan.saas.orgs;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OrgRepository extends JpaRepository<Org, UUID> {

    // "Membership" is resolved by jpa entity name at query time, not a java import,
    // which keeps this package from having to depend on the rbac package directly
    @Query("select o from Org o join Membership m on m.id.orgId = o.id where m.id.userId = :userId order by o.createdAt desc")
    List<Org> findAllForMember(@Param("userId") UUID userId);

    Optional<Org> findByStripeCustomerId(String stripeCustomerId);

    Optional<Org> findByStripeSubscriptionId(String stripeSubscriptionId);
}
