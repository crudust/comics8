from __future__ import annotations

from datetime import datetime
from typing import Any
from sqlalchemy import (
    BigInteger,
    Boolean,
    DateTime,
    ForeignKey,
    Index,
    Integer,
    JSON,
    String,
    Text,
    func,
)
from sqlalchemy.orm import Mapped, mapped_column, relationship
from app.database import Base


class User(Base):
    __tablename__ = "users"

    user_id: Mapped[str] = mapped_column(String(64), primary_key=True)
    sync_key: Mapped[str | None] = mapped_column(Text, nullable=True, unique=True, index=True)
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True),
        server_default=func.now(),
        nullable=False,
    )
    updated_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True),
        server_default=func.now(),
        onupdate=func.now(),
        nullable=False,
    )

    # Relationships
    subscriptions: Mapped[list[Subscription]] = relationship(
        "Subscription",
        back_populates="user",
        cascade="all, delete-orphan",
    )
    favorites: Mapped[list[UserFavorite]] = relationship(
        "UserFavorite",
        back_populates="user",
        cascade="all, delete-orphan",
    )
    history: Mapped[list[UserHistory]] = relationship(
        "UserHistory",
        back_populates="user",
        cascade="all, delete-orphan",
    )
    read_episodes: Mapped[list[UserReadEpisode]] = relationship(
        "UserReadEpisode",
        back_populates="user",
        cascade="all, delete-orphan",
    )
    reader_settings: Mapped[list[UserReaderSetting]] = relationship(
        "UserReaderSetting",
        back_populates="user",
        cascade="all, delete-orphan",
    )
    tombstones: Mapped[list[UserTombstone]] = relationship(
        "UserTombstone",
        back_populates="user",
        cascade="all, delete-orphan",
    )


class Subscription(Base):
    __tablename__ = "subscriptions"

    id: Mapped[int] = mapped_column(
        BigInteger().with_variant(Integer, "sqlite"),
        primary_key=True,
        autoincrement=True,
    )
    user_id: Mapped[str] = mapped_column(
        String(64),
        ForeignKey("users.user_id", ondelete="CASCADE"),
        nullable=False,
        index=True,
    )
    original_transaction_id: Mapped[str] = mapped_column(
        String(255),
        unique=True,
        nullable=False,
        index=True,
    )
    purchase_token: Mapped[str | None] = mapped_column(Text, nullable=True, index=True)
    product_id: Mapped[str] = mapped_column(String(128), nullable=False)
    base_plan_id: Mapped[str | None] = mapped_column(String(128), nullable=True)
    status: Mapped[str] = mapped_column(
        String(32),
        nullable=False,
        default="ACTIVE",
    )  # ACTIVE, CANCELED, EXPIRED, IN_GRACE_PERIOD, ON_HOLD, PAUSED
    is_active: Mapped[bool] = mapped_column(Boolean, nullable=False, default=False, index=True)
    auto_renewing: Mapped[bool] = mapped_column(Boolean, nullable=False, default=True)
    start_time: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)
    expiry_time: Mapped[datetime] = mapped_column(
        DateTime(timezone=True),
        nullable=False,
        index=True,
    )
    canceled_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)
    payment_state: Mapped[str | None] = mapped_column(String(32), nullable=True)
    acknowledgement_state: Mapped[str | None] = mapped_column(String(32), nullable=True)
    latest_order_id: Mapped[str | None] = mapped_column(String(255), nullable=True)
    raw_data: Mapped[dict[str, Any] | None] = mapped_column(JSON, nullable=True)
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True),
        server_default=func.now(),
        nullable=False,
    )
    updated_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True),
        server_default=func.now(),
        onupdate=func.now(),
        nullable=False,
    )

    user: Mapped[User] = relationship("User", back_populates="subscriptions")

    __table_args__ = (
        Index("idx_subscriptions_user_active", "user_id", "is_active"),
    )


class SubscriptionEvent(Base):
    __tablename__ = "subscription_events"

    id: Mapped[int] = mapped_column(
        BigInteger().with_variant(Integer, "sqlite"),
        primary_key=True,
        autoincrement=True,
    )
    user_id: Mapped[str | None] = mapped_column(
        String(64),
        ForeignKey("users.user_id", ondelete="SET NULL"),
        nullable=True,
        index=True,
    )
    original_transaction_id: Mapped[str] = mapped_column(String(255), nullable=False, index=True)
    event_type: Mapped[str] = mapped_column(String(64), nullable=False)
    notification_type: Mapped[int | None] = mapped_column(Integer, nullable=True)
    purchase_token: Mapped[str | None] = mapped_column(Text, nullable=True)
    raw_payload: Mapped[dict[str, Any] | None] = mapped_column(JSON, nullable=True)
    event_time: Mapped[datetime] = mapped_column(
        DateTime(timezone=True),
        server_default=func.now(),
        nullable=False,
    )

    __table_args__ = (
        Index("idx_sub_events_tx", "original_transaction_id", "event_time"),
    )


class UserFavorite(Base):
    __tablename__ = "user_favorites"

    user_id: Mapped[str] = mapped_column(
        String(64),
        ForeignKey("users.user_id", ondelete="CASCADE"),
        primary_key=True,
    )
    source_id: Mapped[str] = mapped_column(String(32), primary_key=True, default="eleven")
    toon_id: Mapped[str] = mapped_column(String(128), primary_key=True)
    title: Mapped[str] = mapped_column(Text, nullable=False, default="")
    thumb_url: Mapped[str | None] = mapped_column(Text, nullable=True, default="")
    href: Mapped[str | None] = mapped_column(Text, nullable=True, default="")
    genre: Mapped[str | None] = mapped_column(Text, nullable=True, default="")
    updated_at: Mapped[str | None] = mapped_column(Text, nullable=True)
    saved_at: Mapped[int] = mapped_column(BigInteger, nullable=False, default=0)
    sync_updated_at: Mapped[int] = mapped_column(BigInteger, nullable=False, default=0, index=True)

    user: Mapped[User] = relationship("User", back_populates="favorites")

    __table_args__ = (
        Index("idx_user_favorites_sync", "user_id", "sync_updated_at"),
        Index("idx_user_favorites_saved", "user_id", "saved_at"),
    )


class UserHistory(Base):
    __tablename__ = "user_history"

    user_id: Mapped[str] = mapped_column(
        String(64),
        ForeignKey("users.user_id", ondelete="CASCADE"),
        primary_key=True,
    )
    source_id: Mapped[str] = mapped_column(String(32), primary_key=True, default="eleven")
    toon_id: Mapped[str] = mapped_column(String(128), primary_key=True)
    toon_title: Mapped[str] = mapped_column(Text, nullable=False, default="")
    toon_thumb_url: Mapped[str | None] = mapped_column(Text, nullable=True, default="")
    toon_href: Mapped[str | None] = mapped_column(Text, nullable=True, default="")
    last_wr_id: Mapped[str] = mapped_column(Text, nullable=False, default="")
    last_episode_title: Mapped[str] = mapped_column(Text, nullable=False, default="")
    last_episode_href: Mapped[str | None] = mapped_column(Text, nullable=True, default="")
    last_read_order: Mapped[int] = mapped_column(Integer, nullable=False, default=1)
    total_episodes: Mapped[int] = mapped_column(Integer, nullable=False, default=1)
    last_read_at: Mapped[int] = mapped_column(BigInteger, nullable=False, default=0)
    next_wr_id: Mapped[str | None] = mapped_column(Text, nullable=True)
    next_episode_title: Mapped[str | None] = mapped_column(Text, nullable=True)
    next_episode_href: Mapped[str | None] = mapped_column(Text, nullable=True)
    has_new: Mapped[bool] = mapped_column(Boolean, nullable=False, default=False)
    sync_updated_at: Mapped[int] = mapped_column(BigInteger, nullable=False, default=0, index=True)

    user: Mapped[User] = relationship("User", back_populates="history")

    __table_args__ = (
        Index("idx_user_history_sync", "user_id", "sync_updated_at"),
        Index("idx_user_history_read_at", "user_id", "last_read_at"),
    )


class UserReadEpisode(Base):
    __tablename__ = "user_read_episodes"

    user_id: Mapped[str] = mapped_column(
        String(64),
        ForeignKey("users.user_id", ondelete="CASCADE"),
        primary_key=True,
    )
    source_id: Mapped[str] = mapped_column(String(32), primary_key=True, default="eleven")
    toon_id: Mapped[str] = mapped_column(String(128), primary_key=True)
    wr_id: Mapped[str] = mapped_column(String(128), primary_key=True)
    read_at: Mapped[int] = mapped_column(BigInteger, nullable=False, default=0)
    last_page: Mapped[int] = mapped_column(Integer, nullable=False, default=0)
    sync_updated_at: Mapped[int] = mapped_column(BigInteger, nullable=False, default=0, index=True)

    user: Mapped[User] = relationship("User", back_populates="read_episodes")

    __table_args__ = (
        Index("idx_user_read_episodes_sync", "user_id", "sync_updated_at"),
    )


class UserReaderSetting(Base):
    __tablename__ = "user_reader_settings"

    user_id: Mapped[str] = mapped_column(
        String(64),
        ForeignKey("users.user_id", ondelete="CASCADE"),
        primary_key=True,
    )
    source_id: Mapped[str] = mapped_column(String(32), primary_key=True, default="eleven")
    toon_id: Mapped[str] = mapped_column(String(128), primary_key=True)
    view_mode: Mapped[str] = mapped_column(String(32), nullable=False, default="SINGLE")
    read_direction: Mapped[str] = mapped_column(String(32), nullable=False, default="RIGHT_TO_LEFT")
    split_mode: Mapped[str] = mapped_column(String(32), nullable=False, default="FIT")
    updated_at: Mapped[int] = mapped_column(BigInteger, nullable=False, default=0)
    sync_updated_at: Mapped[int] = mapped_column(BigInteger, nullable=False, default=0, index=True)

    user: Mapped[User] = relationship("User", back_populates="reader_settings")

    __table_args__ = (
        Index("idx_user_reader_settings_sync", "user_id", "sync_updated_at"),
    )


class UserTombstone(Base):
    __tablename__ = "user_tombstones"

    user_id: Mapped[str] = mapped_column(
        String(64),
        ForeignKey("users.user_id", ondelete="CASCADE"),
        primary_key=True,
    )
    entity_type: Mapped[str] = mapped_column(String(32), primary_key=True)
    entity_id: Mapped[str] = mapped_column(String(255), primary_key=True)
    deleted_at: Mapped[int] = mapped_column(BigInteger, nullable=False, default=0, index=True)

    user: Mapped[User] = relationship("User", back_populates="tombstones")

    __table_args__ = (
        Index("idx_user_tombstones_deleted", "user_id", "deleted_at"),
    )


class PairingCode(Base):
    __tablename__ = "pairing_codes"

    code: Mapped[str] = mapped_column(String(16), primary_key=True)
    sync_key: Mapped[str] = mapped_column(Text, nullable=False)
    expires_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False, index=True)
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True),
        server_default=func.now(),
        nullable=False,
    )
