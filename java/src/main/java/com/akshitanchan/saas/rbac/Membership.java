package com.akshitanchan.saas.rbac;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.hibernate.annotations.Generated;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.generator.EventType;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "memberships")
public class Membership {

    @EmbeddedId
    private MembershipId id;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(nullable = false, columnDefinition = "role")
    private Role role = Role.member;

    @Generated(event = EventType.INSERT)
    @Column(name = "created_at", insertable = false, updatable = false, nullable = false)
    private OffsetDateTime createdAt;

    protected Membership() {
    }

    public Membership(UUID userId, UUID orgId, Role role) {
        this.id = new MembershipId(userId, orgId);
        this.role = role;
    }

    public MembershipId getId() {
        return id;
    }

    public UUID getUserId() {
        return id.getUserId();
    }

    public UUID getOrgId() {
        return id.getOrgId();
    }

    public Role getRole() {
        return role;
    }

    public void setRole(Role role) {
        this.role = role;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }
}
