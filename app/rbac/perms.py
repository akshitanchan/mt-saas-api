from app.models.enums import Role

PERMS: dict[str, set[Role]] = {
    "org:invite": {Role.owner, Role.admin},
    "org:view": {Role.owner, Role.admin, Role.member},

    "projects:create": {Role.owner, Role.admin},
    "projects:read": {Role.owner, Role.admin, Role.member},
    "projects:update": {Role.owner, Role.admin},
    "projects:delete": {Role.owner, Role.admin},

    "tasks:create": {Role.owner, Role.admin, Role.member},
    "tasks:read": {Role.owner, Role.admin, Role.member},
    "tasks:update": {Role.owner, Role.admin, Role.member},
    "tasks:delete": {Role.owner, Role.admin},
}
