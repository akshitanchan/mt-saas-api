from pydantic_settings import BaseSettings, SettingsConfigDict

class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=".env", extra="ignore")

    app_env: str = "dev"
    app_host: str = "0.0.0.0"
    app_port: int = 8000

    base_url: str = "http://localhost:8000"
    database_url: str = "postgresql+psycopg://app:app@db:5432/app"
    redis_url: str = "redis://redis:6379/0"

    jwt_secret: str = "dev-secret-change-me"
    jwt_issuer: str = "mt-saas-api"
    jwt_audience: str = "mt-saas-api"
    jwt_expires_minutes: int = 60

    magic_link_expires_minutes: int = 15
    magic_link_pepper: str = "dev-pepper-change-me"

    STRIPE_WEBHOOK_SECRET: str | None = None
    
    # rate limiting (redis)
    rate_limit_enabled: bool = True
    rate_limit_auth_request_link_per_min: int = 20
    rate_limit_auth_redeem_per_min: int = 30
    rate_limit_webhooks_per_min: int = 60

settings = Settings()
