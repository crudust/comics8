from __future__ import annotations

import os
from functools import lru_cache
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(
        env_file=".env",
        env_file_encoding="utf-8",
        extra="ignore",
        case_sensitive=False,
    )

    # Server settings
    HOST: str = "0.0.0.0"
    PORT: int = 8905
    DEBUG: bool = False

    # Database settings
    # Default to asyncpg PostgreSQL; sqlite+aiosqlite can be used in test environments
    DATABASE_URL: str
    DB_ECHO: bool = False
    DB_POOL_SIZE: int = 20
    DB_MAX_OVERFLOW: int = 10
    DB_POOL_TIMEOUT: int = 30

    # Data & Storage directories
    DATA_DIR: str = os.getenv(
        "DATA_DIR",
        os.path.expanduser("~/comics8-sync/data"),
    )
    DOWNLOADS_DIR_NAME: str = "downloads"
    VERSION_FILE_NAME: str = "version.json"

    # Sync protocol settings
    DEFAULT_SOURCE_ID: str = "eleven"
    SYNC_SCHEMA_CURRENT: int = 2
    SYNC_SCHEMA_HEADER: str = "X-Comics8-Sync-Schema"
    PAIR_CODE_TTL_SECONDS: int = 300
    PAIR_CONFIRM_MAX_ATTEMPTS: int = 10
    PAIR_CONFIRM_WINDOW_SECONDS: int = 60
    MAX_SYNC_REQUEST_BYTES: int = 16 * 1024 * 1024
    MAX_SYNC_ITEMS_PER_REQUEST: int = 10_000
    MAX_SYNC_ITEMS_PER_USER: int = 100_000
    MAX_SYNC_TEXT_LENGTH: int = 8_192
    # Proxy settings
    DEFAULT_REFERER: str = ""
    PROXY_TIMEOUT_SECONDS: int = 20
    MAX_PROXY_REDIRECTS: int = 5
    MAX_PROXY_RESPONSE_BYTES: int = 2 * 1024 * 1024
    MAX_PROXY_CONCURRENT_REQUESTS: int = 16
    MAX_PROXY_STREAM_SECONDS: int = 30
    PROXY_RATE_LIMIT_PER_MINUTE: int = 120
    DEFAULT_PROXY_UA: str = (
        "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) "
        "Chrome/126.0.0.0 Mobile Safari/537.36"
    )

    # Google Play Billing & Pub/Sub
    GOOGLE_APPLICATION_CREDENTIALS: str | None = os.getenv("GOOGLE_APPLICATION_CREDENTIALS")
    GOOGLE_PLAY_PACKAGE_NAME: str = os.getenv("GOOGLE_PLAY_PACKAGE_NAME", "com.comics8.monitor")
    PUBSUB_SUBSCRIPTION_ID: str | None = os.getenv("PUBSUB_SUBSCRIPTION_ID")
    GOOGLE_PUBSUB_SUBSCRIPTION: str | None = os.getenv("GOOGLE_PUBSUB_SUBSCRIPTION")

    @property
    def downloads_dir(self) -> str:
        path = os.path.join(self.DATA_DIR, self.DOWNLOADS_DIR_NAME)
        os.makedirs(path, exist_ok=True)
        return path

    @property
    def version_file(self) -> str:
        return os.path.join(self.DATA_DIR, self.VERSION_FILE_NAME)

    @property
    def pubsub_subscription(self) -> str | None:
        return self.PUBSUB_SUBSCRIPTION_ID or self.GOOGLE_PUBSUB_SUBSCRIPTION


@lru_cache()
def get_settings() -> Settings:
    return Settings()
