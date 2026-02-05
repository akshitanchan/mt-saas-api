from fastapi import FastAPI

from app.routes.auth import router as auth_router
from app.routes.health import router as health_router
from app.routes.orgs import router as orgs_router
from app.routes.projects import router as projects_router
from app.routes.tasks import router as tasks_router
from app.routes.webhooks import router as webhooks_router

def create_app() -> FastAPI:
    app = FastAPI(title="mt-saas-api", version="0.1.0")
    app.include_router(health_router)
    app.include_router(auth_router)
    app.include_router(orgs_router)
    app.include_router(projects_router)
    app.include_router(tasks_router)
    app.include_router(webhooks_router)
    return app

app = create_app()
