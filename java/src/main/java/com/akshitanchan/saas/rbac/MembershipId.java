package com.akshitanchan.saas.rbac;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

@Embeddable
public class MembershipId implements Serializable {

    @Column(name = "user_id")
    private UUID userId;

    @Column(name = "org_id")
    private UUID orgId;

    protected MembershipId() {
    }

    public MembershipId(UUID userId, UUID orgId) {
        this.userId = userId;
        this.orgId = orgId;
    }

    public UUID getUserId() {
        return userId;
    }

    public UUID getOrgId() {
        return orgId;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof MembershipId that)) {
            return false;
        }
        return Objects.equals(userId, that.userId) && Objects.equals(orgId, that.orgId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(userId, orgId);
    }
}
