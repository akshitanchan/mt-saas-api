package com.akshitanchan.saas.rbac;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

// mirrors app/rbac/perms.py: which roles carry which action
public final class Perms {

    private static final Map<String, Set<Role>> ALLOWED = Map.ofEntries(
            Map.entry("org:invite", EnumSet.of(Role.owner, Role.admin)),
            Map.entry("org:view", EnumSet.allOf(Role.class)),
            Map.entry("projects:create", EnumSet.of(Role.owner, Role.admin)),
            Map.entry("projects:read", EnumSet.allOf(Role.class)),
            Map.entry("projects:update", EnumSet.of(Role.owner, Role.admin)),
            Map.entry("projects:delete", EnumSet.of(Role.owner, Role.admin)),
            Map.entry("tasks:create", EnumSet.allOf(Role.class)),
            Map.entry("tasks:read", EnumSet.allOf(Role.class)),
            Map.entry("tasks:update", EnumSet.allOf(Role.class)),
            Map.entry("tasks:delete", EnumSet.of(Role.owner, Role.admin)));

    private Perms() {
    }

    public static boolean allows(String action, Role role) {
        Set<Role> allowed = ALLOWED.get(action);
        if (allowed == null) {
            throw new IllegalStateException("unknown permission action: " + action);
        }
        return allowed.contains(role);
    }
}
