from enum import Enum

class Role(str, Enum):
    owner = "owner"
    admin = "admin"
    member = "member"

class Plan(str, Enum):
    free = "free"
    pro = "pro"

class SubscriptionStatus(str, Enum):
    none = "none"
    incomplete = "incomplete"
    trialing = "trialing"
    active = "active"
    past_due = "past_due"
    canceled = "canceled"
    unpaid = "unpaid"

class TaskStatus(str, Enum):
    todo = "todo"
    doing = "doing"
    done = "done"
