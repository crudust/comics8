from __future__ import annotations

import asyncio
from collections import OrderedDict, deque
import hashlib
import json
import logging
import re
import secrets
import time
from urllib.parse import urlparse
from dataclasses import dataclass
from datetime import datetime, timezone
from typing import Any
from fastapi import APIRouter, Depends, Header, HTTPException, Request, Response, status
from fastapi.responses import JSONResponse
from pydantic import BaseModel, Field, ValidationError
from sqlalchemy import delete, func, select, text, tuple_
from sqlalchemy.dialects.postgresql import insert as pg_insert
from sqlalchemy.ext.asyncio import AsyncSession
from app.config import get_settings
from app.database import get_db
from app.models import (
    PairingCode,
    Subscription,
    ToonCatalog,
    ToonSource,
    User,
    UserFavorite,
    UserHistory,
    UserReadEpisode,
    UserReaderSetting,
    UserTombstone,
)

logger = logging.getLogger("comics8.sync")
router = APIRouter(tags=["Sync"])

_PREFETCH_CHUNK_SIZE = 400
_SUPPORTED_TOMBSTONE_TYPES = {"FAVORITE", "HISTORY", "EPISODE"}
_SYNC_BUCKETS = ("favorites", "history", "readEpisodes", "readerSettings", "tombstones", "catalog")
_SYNC_STRING_LIMITS = {
    "sourceId": 255,
    "id": 512,
    "toonId": 512,
    "wrId": 1024,
    "entityId": 1024,
    "entityType": 32,
}


@dataclass(frozen=True)
class SyncIdentity:
    user_id: str
    sync_key: str


def get_safe_user_id(key: str) -> str:
    cleaned = re.sub(r"[^a-zA-Z0-9_\-]", "", key.strip())
    if 3 <= len(cleaned) <= 64:
        return cleaned
    return hashlib.sha256(key.strip().encode("utf-8")).hexdigest()[:24]


def user_accepts_sync_key(user: User, raw_key: str) -> bool:
    if user.sync_key is not None:
        return user.sync_key == raw_key
    # Rows created before sync_key was persisted remain bound to their canonical user ID.
    if user.user_id == raw_key:
        return True
    cleaned = re.sub(r"[^a-zA-Z0-9_\-]", "", raw_key)
    return not 3 <= len(cleaned) <= 64 and get_safe_user_id(raw_key) == user.user_id


def parse_entity_id(entity_id: str | None) -> tuple[str, str]:
    settings = get_settings()
    raw = (entity_id or "").strip()
    if ":" in raw:
        source, local = raw.split(":", 1)
        if source and local:
            return source, local
    return settings.DEFAULT_SOURCE_ID, raw


def row_source_id(row: dict[str, Any] | None) -> str:
    settings = get_settings()
    if not row:
        return settings.DEFAULT_SOURCE_ID
    return (row.get("sourceId") or settings.DEFAULT_SOURCE_ID).strip() or settings.DEFAULT_SOURCE_ID


def accept_inbound_row(row: dict[str, Any]) -> bool:
    source = row_source_id(row)
    return source != "local"


def favorite_local_id(row: dict[str, Any]) -> str | None:
    value = row.get("id") or row.get("toonId")
    if value is None:
        return None
    text = str(value).strip()
    return text or None


def emit_favorite(fav: UserFavorite | dict[str, Any]) -> dict[str, Any] | None:
    if isinstance(fav, UserFavorite):
        return {
            "id": fav.toon_id,
            "sourceId": fav.source_id,
            "title": fav.title or "",
            "thumbUrl": fav.thumb_url or "",
            "href": fav.href or "",
            "genre": fav.genre or "",
            "updatedAt": fav.updated_at,
            "savedAt": fav.saved_at,
        }
    source_id = row_source_id(fav)
    toon_id = favorite_local_id(fav)
    if not toon_id:
        return None
    return {
        "id": toon_id,
        "sourceId": source_id,
        "title": fav.get("title", ""),
        "thumbUrl": fav.get("thumbUrl", ""),
        "href": fav.get("href", ""),
        "genre": fav.get("genre", ""),
        "updatedAt": fav.get("updatedAt"),
        "savedAt": fav.get("savedAt"),
    }


def emit_history(h: UserHistory | dict[str, Any]) -> dict[str, Any] | None:
    if isinstance(h, UserHistory):
        return {
            "sourceId": h.source_id,
            "toonId": h.toon_id,
            "toonTitle": h.toon_title or "",
            "toonThumbUrl": h.toon_thumb_url or "",
            "toonHref": h.toon_href or "",
            "lastWrId": h.last_wr_id or "",
            "lastEpisodeTitle": h.last_episode_title or "",
            "lastEpisodeHref": h.last_episode_href or "",
            "lastReadOrder": h.last_read_order,
            "totalEpisodes": h.total_episodes,
            "lastReadAt": h.last_read_at,
            "nextWrId": h.next_wr_id,
            "nextEpisodeTitle": h.next_episode_title,
            "nextEpisodeHref": h.next_episode_href,
            "hasNew": bool(h.has_new),
        }
    source_id = row_source_id(h)
    out = dict(h)
    out["sourceId"] = source_id
    if "hasNew" in out:
        out["hasNew"] = bool(out.get("hasNew"))
    return out


def emit_episode(ep: UserReadEpisode | dict[str, Any]) -> dict[str, Any] | None:
    if isinstance(ep, UserReadEpisode):
        return {
            "sourceId": ep.source_id,
            "toonId": ep.toon_id,
            "wrId": ep.wr_id,
            "readAt": ep.read_at,
            "lastPage": ep.last_page,
        }
    source_id = row_source_id(ep)
    out = dict(ep)
    out["sourceId"] = source_id
    return out


def emit_setting(s: UserReaderSetting | dict[str, Any]) -> dict[str, Any] | None:
    if isinstance(s, UserReaderSetting):
        return {
            "sourceId": s.source_id,
            "toonId": s.toon_id,
            "viewMode": s.view_mode,
            "readDirection": s.read_direction,
            "splitMode": s.split_mode,
            "updatedAt": s.updated_at,
        }
    source_id = row_source_id(s)
    out = dict(s)
    out["sourceId"] = source_id
    return out


def emit_tombstone(t: UserTombstone | dict[str, Any]) -> dict[str, Any] | None:
    if isinstance(t, UserTombstone):
        return {
            "entityType": t.entity_type,
            "entityId": t.entity_id,
            "deletedAt": t.deleted_at,
        }
    entity_id = t.get("entityId")
    if not entity_id:
        return None
    return {
        "entityType": t.get("entityType"),
        "entityId": entity_id,
        "deletedAt": t.get("deletedAt"),
    }


async def check_user_is_pro(session: AsyncSession, user_id: str) -> bool:
    now = datetime.now(timezone.utc)
    stmt = (
        select(Subscription.id)
        .where(
            Subscription.user_id == user_id,
            Subscription.is_active == True,
            Subscription.expiry_time > now,
        )
        .limit(1)
    )
    res = await session.execute(stmt)
    return res.scalar_one_or_none() is not None


# Authentication resolves identity only. Durable writes own account creation.
async def get_sync_user(
    authorization: str | None = Header(None),
    x_sync_key: str | None = Header(None, alias="X-Sync-Key"),
    session: AsyncSession = Depends(get_db),
) -> SyncIdentity:
    raw_key = None
    if authorization and authorization.startswith("Bearer "):
        raw_key = authorization[7:].strip()
    elif x_sync_key:
        raw_key = x_sync_key.strip()

    if not raw_key:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail={"error": "Unauthorized", "message": "Missing sync key"},
        )

    user_id = get_safe_user_id(raw_key)
    user = await session.get(User, user_id)
    if user is not None and not user_accepts_sync_key(user, raw_key):
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail={"error": "Unauthorized", "message": "Invalid sync key"},
        )

    return SyncIdentity(user_id=user_id, sync_key=raw_key)


async def get_existing_sync_user(
    identity: SyncIdentity = Depends(get_sync_user),
    session: AsyncSession = Depends(get_db),
) -> SyncIdentity:
    if await session.get(User, identity.user_id) is None:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail={"error": "Unauthorized", "message": "Sync account has no stored data"},
        )
    return identity


async def ensure_user_for_write(session: AsyncSession, identity: SyncIdentity) -> User:
    """Create and bind a sync account only for an authorized durable write."""
    if session.bind is not None and session.bind.dialect.name == "postgresql":
        lock_id = int.from_bytes(
            hashlib.sha256(identity.user_id.encode("utf-8")).digest()[:8],
            "big",
            signed=True,
        )
        await session.execute(
            text("SELECT pg_advisory_xact_lock(:lock_id)"),
            {"lock_id": lock_id},
        )

    user = await session.get(User, identity.user_id)
    if user is not None:
        if not user_accepts_sync_key(user, identity.sync_key):
            raise HTTPException(
                status_code=status.HTTP_401_UNAUTHORIZED,
                detail={"error": "Unauthorized", "message": "Invalid sync key"},
            )
        if user.sync_key is None:
            user.sync_key = identity.sync_key
            await session.flush()
        return user

    user = User(user_id=identity.user_id, sync_key=identity.sync_key)
    session.add(user)
    await session.flush()
    return user


async def read_bounded_json(request: Request) -> dict[str, Any]:
    limit = get_settings().MAX_SYNC_REQUEST_BYTES
    content_length = request.headers.get("content-length")
    if content_length:
        try:
            if int(content_length) > limit:
                raise HTTPException(status_code=413, detail="Sync request is too large")
        except ValueError:
            raise HTTPException(status_code=400, detail="Invalid Content-Length")

    body = bytearray()
    async for chunk in request.stream():
        body.extend(chunk)
        if len(body) > limit:
            raise HTTPException(status_code=413, detail="Sync request is too large")
    if not body:
        raise HTTPException(status_code=400, detail="Empty body")
    try:
        data = json.loads(body)
    except (UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise HTTPException(status_code=400, detail=f"Invalid JSON: {exc}")
    if not isinstance(data, dict):
        raise HTTPException(status_code=400, detail="Sync payload must be an object")
    request.state.sync_body_bytes = len(body)
    return data


def validate_sync_payload(data: dict[str, Any]) -> None:
    settings = get_settings()
    item_count = 0
    for bucket in _SYNC_BUCKETS:
        rows = data.get(bucket, [])
        if not isinstance(rows, list):
            raise HTTPException(status_code=400, detail=f"'{bucket}' must be an array")
        item_count += len(rows)
        if item_count > settings.MAX_SYNC_ITEMS_PER_REQUEST:
            raise HTTPException(status_code=413, detail="Too many sync items")
        for row in rows:
            if not isinstance(row, dict):
                raise HTTPException(status_code=400, detail=f"'{bucket}' items must be objects")
            for field, value in row.items():
                if isinstance(value, str) and len(value) > settings.MAX_SYNC_TEXT_LENGTH:
                    raise HTTPException(status_code=413, detail="Sync text field is too large")
                field_limit = _SYNC_STRING_LIMITS.get(field)
                if field_limit is not None and value is not None:
                    if not isinstance(value, str):
                        raise HTTPException(status_code=400, detail=f"'{field}' must be a string")
                    if len(value) > field_limit:
                        raise HTTPException(status_code=400, detail=f"'{field}' is too long")


async def count_user_sync_items(session: AsyncSession, user_id: str) -> int:
    models = (UserFavorite, UserHistory, UserReadEpisode, UserReaderSetting, UserTombstone)
    counts = [
        await session.scalar(select(func.count()).select_from(model).where(model.user_id == user_id))
        for model in models
    ]
    return sum(int(value or 0) for value in counts)


async def enforce_user_storage_limit(
    session: AsyncSession,
    user_id: str,
    previous_count: int,
) -> None:
    limit = get_settings().MAX_SYNC_ITEMS_PER_USER
    if limit <= 0:
        return
    await session.flush()
    current_count = await count_user_sync_items(session, user_id)
    if current_count > limit and current_count > previous_count:
        await session.rollback()
        raise HTTPException(status_code=413, detail="Sync account storage limit reached")


# Export full snapshot
async def export_snapshot_for_user(session: AsyncSession, user_id: str) -> dict[str, Any]:
    # Favorites
    fav_stmt = (
        select(UserFavorite)
        .where(UserFavorite.user_id == user_id)
        .order_by(UserFavorite.saved_at.desc())
    )
    fav_res = await session.execute(fav_stmt)
    favs = [emit_favorite(f) for f in fav_res.scalars().all()]
    favs = [f for f in favs if f is not None]

    # History
    hist_stmt = (
        select(UserHistory)
        .where(UserHistory.user_id == user_id)
        .order_by(UserHistory.last_read_at.desc())
    )
    hist_res = await session.execute(hist_stmt)
    hist = [emit_history(h) for h in hist_res.scalars().all()]
    hist = [h for h in hist if h is not None]

    # Read Episodes
    ep_stmt = select(UserReadEpisode).where(UserReadEpisode.user_id == user_id)
    ep_res = await session.execute(ep_stmt)
    eps = [emit_episode(e) for e in ep_res.scalars().all()]
    eps = [e for e in eps if e is not None]

    # Settings
    set_stmt = select(UserReaderSetting).where(UserReaderSetting.user_id == user_id)
    set_res = await session.execute(set_stmt)
    settings = [emit_setting(s) for s in set_res.scalars().all()]
    settings = [s for s in settings if s is not None]

    is_pro = await check_user_is_pro(session, user_id)

    return {
        "version": 2,
        "appName": "Comics8",
        "exportedAt": int(time.time() * 1000),
        "isPro": is_pro,
        "favorites": favs,
        "history": hist,
        "readEpisodes": eps,
        "readerSettings": settings,
    }


def emit_catalog(c: ToonCatalog) -> dict[str, Any]:
    """Single wire shape for a catalog row, including the verified listing shape."""
    return {
        "sourceId": c.source_id,
        "toonId": c.toon_id,
        "totalEpisodes": c.total_episodes,
        "updatedAt": c.updated_at,
        "exact": bool(c.exact),
        "lastPage": c.last_page,
        "lastPageItemCount": c.last_page_item_count,
    }


async def upsert_catalog_from_payload(
    session: AsyncSession,
    history_rows: list[dict[str, Any]],
    catalog_rows: list[dict[str, Any]],
    now_ms: int,
) -> None:
    merged: dict[tuple[str, str], dict[str, Any]] = {}

    # Mirrors EpisodeTotal.merge on the clients: an exact observation wins outright (it may lower a
    # stale total), while a lower bound may only raise another lower bound.
    def add_entry(
        source_id: str,
        toon_id: str,
        total_episodes: int,
        updated_at: int,
        exact: bool,
        last_page: int,
        last_page_item_count: int,
    ) -> None:
        if not toon_id or not (0 < total_episodes <= 100_000):
            return
        if len(source_id) > 255 or len(toon_id) > 512:
            return
        key = (source_id, toon_id)
        # A listing shape is only meaningful together with an exact total.
        # The shape must be able to reproduce the total; otherwise it is noise, not a verification.
        shape_ok = (
            exact
            and 0 < last_page <= 100_000
            and 0 <= last_page_item_count <= 100_000
            # A listing reported as multi-page cannot have an empty tail, and the shape must be able
            # to reproduce the total it is stored with.
            and (last_page == 1 or 1 <= last_page_item_count < total_episodes)
        )
        prev = merged.get(key)
        if prev is None:
            merged[key] = {
                "source_id": source_id,
                "toon_id": toon_id,
                "total_episodes": total_episodes,
                "updated_at": updated_at,
                "exact": bool(exact),
                "last_page": last_page if shape_ok else 0,
                "last_page_item_count": last_page_item_count if shape_ok else -1,
            }
            return
        if exact:
            prev["total_episodes"] = total_episodes
            prev["updated_at"] = updated_at
            prev["exact"] = True
            if shape_ok:
                prev["last_page"] = last_page
                prev["last_page_item_count"] = last_page_item_count
        elif not prev["exact"] and total_episodes > prev["total_episodes"]:
            prev["total_episodes"] = total_episodes
            prev["updated_at"] = updated_at
        elif total_episodes == prev["total_episodes"]:
            prev["updated_at"] = max(prev["updated_at"], updated_at)

    for h in history_rows:
        tid = str(h.get("toonId") or h.get("id") or "").strip()
        add_entry(
            source_id=row_source_id(h),
            toon_id=tid,
            total_episodes=int(h.get("totalEpisodes", 0)),
            updated_at=int(h.get("lastReadAt", now_ms)),
            exact=False,
            last_page=0,
            last_page_item_count=-1,
        )

    for c in catalog_rows:
        tid = str(c.get("toonId") or c.get("id") or "").strip()
        add_entry(
            source_id=row_source_id(c),
            toon_id=tid,
            total_episodes=int(c.get("totalEpisodes", 0)),
            updated_at=int(c.get("updatedAt", now_ms)),
            exact=bool(c.get("exact", False)),
            last_page=int(c.get("lastPage", 0) or 0),
            last_page_item_count=int(c.get("lastPageItemCount", -1) or -1),
        )

    if not merged:
        return

    cat_stmt = pg_insert(ToonCatalog).values(list(merged.values()))
    # An exact row always wins so a deleted/renamed episode range can correct the catalog; a lower
    # bound may only overwrite another lower bound with a bigger count.
    cat_stmt = cat_stmt.on_conflict_do_update(
        index_elements=[ToonCatalog.source_id, ToonCatalog.toon_id],
        set_={
            "total_episodes": cat_stmt.excluded.total_episodes,
            "updated_at": cat_stmt.excluded.updated_at,
            "exact": cat_stmt.excluded.exact,
            "last_page": cat_stmt.excluded.last_page,
            "last_page_item_count": cat_stmt.excluded.last_page_item_count,
        },
        where=(
            cat_stmt.excluded.exact
            | (
                (ToonCatalog.exact == False)  # noqa: E712 - SQL boolean expression
                & (cat_stmt.excluded.total_episodes > ToonCatalog.total_episodes)
            )
        ),
    )
    await session.execute(cat_stmt)


# Import full snapshot
async def import_snapshot_for_user(
    session: AsyncSession,
    user_id: str,
    data: dict[str, Any],
    now_ms: int,
    identity: SyncIdentity | None = None,
) -> None:
    favorite_rows = [
        f for f in data.get("favorites", [])
        if accept_inbound_row(f) and favorite_local_id(f)
    ]
    history_rows = [
        h for h in data.get("history", [])
        if accept_inbound_row(h) and h.get("toonId")
    ]
    episode_rows = [
        ep for ep in data.get("readEpisodes", [])
        if accept_inbound_row(ep) and ep.get("toonId") and ep.get("wrId")
    ]
    setting_rows = [
        s for s in data.get("readerSettings", [])
        if accept_inbound_row(s) and s.get("toonId")
    ]

    has_rows = any((favorite_rows, history_rows, episode_rows, setting_rows))
    if identity is not None and has_rows:
        await ensure_user_for_write(session, identity)
    previous_count = await count_user_sync_items(session, user_id) if has_rows else 0

    # 1. Favorites Batch UPSERT
    if favorite_rows:
        fav_values = [
            {
                "user_id": user_id,
                "source_id": row_source_id(f),
                "toon_id": favorite_local_id(f),
                "title": f.get("title", ""),
                "thumb_url": f.get("thumbUrl", ""),
                "href": f.get("href", ""),
                "genre": f.get("genre", ""),
                "updated_at": f.get("updatedAt"),
                "saved_at": int(f.get("savedAt") or now_ms),
                "sync_updated_at": now_ms,
            }
            for f in favorite_rows
        ]
        stmt = pg_insert(UserFavorite).values(fav_values)
        stmt = stmt.on_conflict_do_update(
            index_elements=[UserFavorite.user_id, UserFavorite.source_id, UserFavorite.toon_id],
            set_={
                "title": stmt.excluded.title,
                "thumb_url": stmt.excluded.thumb_url,
                "href": stmt.excluded.href,
                "genre": stmt.excluded.genre,
                "updated_at": stmt.excluded.updated_at,
                "saved_at": stmt.excluded.saved_at,
                "sync_updated_at": stmt.excluded.sync_updated_at,
            },
        )
        await session.execute(stmt)

    # 2. History Batch UPSERT
    if history_rows:
        hist_values = [
            {
                "user_id": user_id,
                "source_id": row_source_id(h),
                "toon_id": h.get("toonId"),
                "toon_title": h.get("toonTitle", ""),
                "toon_thumb_url": h.get("toonThumbUrl", ""),
                "toon_href": h.get("toonHref", ""),
                "last_wr_id": h.get("lastWrId", ""),
                "last_episode_title": h.get("lastEpisodeTitle", ""),
                "last_episode_href": h.get("lastEpisodeHref", ""),
                "last_read_order": int(h.get("lastReadOrder", 1)),
                "total_episodes": int(h.get("totalEpisodes", 1)),
                "last_read_at": int(h.get("lastReadAt") or now_ms),
                "next_wr_id": h.get("nextWrId"),
                "next_episode_title": h.get("nextEpisodeTitle"),
                "next_episode_href": h.get("nextEpisodeHref"),
                "has_new": bool(h.get("hasNew", False)),
                "sync_updated_at": now_ms,
            }
            for h in history_rows
        ]
        stmt = pg_insert(UserHistory).values(hist_values)
        stmt = stmt.on_conflict_do_update(
            index_elements=[UserHistory.user_id, UserHistory.source_id, UserHistory.toon_id],
            set_={
                "toon_title": stmt.excluded.toon_title,
                "toon_thumb_url": stmt.excluded.toon_thumb_url,
                "toon_href": stmt.excluded.toon_href,
                "last_wr_id": stmt.excluded.last_wr_id,
                "last_episode_title": stmt.excluded.last_episode_title,
                "last_episode_href": stmt.excluded.last_episode_href,
                "last_read_order": stmt.excluded.last_read_order,
                "total_episodes": stmt.excluded.total_episodes,
                "last_read_at": stmt.excluded.last_read_at,
                "next_wr_id": stmt.excluded.next_wr_id,
                "next_episode_title": stmt.excluded.next_episode_title,
                "next_episode_href": stmt.excluded.next_episode_href,
                "has_new": stmt.excluded.has_new,
                "sync_updated_at": stmt.excluded.sync_updated_at,
            },
        )
        await session.execute(stmt)

    # Upsert global toon_catalog
    catalog_rows = [r for r in data.get("catalog", []) if accept_inbound_row(r)]
    await upsert_catalog_from_payload(session, history_rows, catalog_rows, now_ms)

    # 3. Read Episodes Batch UPSERT
    if episode_rows:
        ep_values = [
            {
                "user_id": user_id,
                "source_id": row_source_id(ep),
                "toon_id": ep.get("toonId"),
                "wr_id": ep.get("wrId"),
                "read_at": int(ep.get("readAt") or now_ms),
                "last_page": int(ep.get("lastPage", 0)),
                "sync_updated_at": now_ms,
            }
            for ep in episode_rows
        ]
        stmt = pg_insert(UserReadEpisode).values(ep_values)
        stmt = stmt.on_conflict_do_update(
            index_elements=[UserReadEpisode.user_id, UserReadEpisode.source_id, UserReadEpisode.toon_id, UserReadEpisode.wr_id],
            set_={
                "read_at": stmt.excluded.read_at,
                "last_page": stmt.excluded.last_page,
                "sync_updated_at": stmt.excluded.sync_updated_at,
            },
        )
        await session.execute(stmt)

    # 4. Reader Settings Batch UPSERT
    if setting_rows:
        set_values = [
            {
                "user_id": user_id,
                "source_id": row_source_id(s),
                "toon_id": s.get("toonId"),
                "view_mode": s.get("viewMode", "SINGLE"),
                "read_direction": s.get("readDirection", "RIGHT_TO_LEFT"),
                "split_mode": s.get("splitMode", "FIT"),
                "updated_at": int(s.get("updatedAt") or now_ms),
                "sync_updated_at": now_ms,
            }
            for s in setting_rows
        ]
        stmt = pg_insert(UserReaderSetting).values(set_values)
        stmt = stmt.on_conflict_do_update(
            index_elements=[UserReaderSetting.user_id, UserReaderSetting.source_id, UserReaderSetting.toon_id],
            set_={
                "view_mode": stmt.excluded.view_mode,
                "read_direction": stmt.excluded.read_direction,
                "split_mode": stmt.excluded.split_mode,
                "updated_at": stmt.excluded.updated_at,
                "sync_updated_at": stmt.excluded.sync_updated_at,
            },
        )
        await session.execute(stmt)

    if has_rows:
        await enforce_user_storage_limit(session, user_id, previous_count)
    await session.commit()


# Apply Delta changes
async def apply_delta_for_user(
    session: AsyncSession,
    user_id: str,
    changes: dict[str, Any],
    now_ms: int,
    identity: SyncIdentity | None = None,
) -> None:
    tombstone_rows: list[tuple[dict[str, Any], str, str, str]] = []
    for t in changes.get("tombstones", []):
        entity_type = (t.get("entityType") or "").upper()
        source_id, local_id = parse_entity_id(t.get("entityId", ""))
        if entity_type in _SUPPORTED_TOMBSTONE_TYPES and local_id and source_id != "local":
            tombstone_rows.append((t, entity_type, source_id, local_id))

    favorite_rows = [
        f for f in changes.get("favorites", [])
        if accept_inbound_row(f) and favorite_local_id(f)
    ]
    history_rows = [
        h for h in changes.get("history", [])
        if accept_inbound_row(h) and h.get("toonId")
    ]
    episode_rows = [
        ep for ep in changes.get("readEpisodes", [])
        if accept_inbound_row(ep) and ep.get("toonId") and ep.get("wrId")
    ]
    setting_rows = [
        s for s in changes.get("readerSettings", [])
        if accept_inbound_row(s) and s.get("toonId")
    ]

    has_rows = any((tombstone_rows, favorite_rows, history_rows, episode_rows, setting_rows))
    if identity is not None and has_rows:
        await ensure_user_for_write(session, identity)
    previous_count = await count_user_sync_items(session, user_id) if has_rows else 0

    # A. Tombstones Batch UPSERT & Deletions
    if tombstone_rows:
        tomb_values = [
            {
                "user_id": user_id,
                "entity_type": entity_type,
                "entity_id": f"{source_id}:{local_id}",
                "deleted_at": int(t.get("deletedAt", now_ms)),
            }
            for t, entity_type, source_id, local_id in tombstone_rows
        ]
        t_stmt = pg_insert(UserTombstone).values(tomb_values)
        t_stmt = t_stmt.on_conflict_do_update(
            index_elements=[UserTombstone.user_id, UserTombstone.entity_type, UserTombstone.entity_id],
            set_={
                "deleted_at": func.greatest(UserTombstone.deleted_at, t_stmt.excluded.deleted_at),
            },
        )
        await session.execute(t_stmt)

        fav_deletes = [(s, loc) for _, et, s, loc in tombstone_rows if et == "FAVORITE"]
        if fav_deletes:
            for offset in range(0, len(fav_deletes), _PREFETCH_CHUNK_SIZE):
                chunk = fav_deletes[offset:offset + _PREFETCH_CHUNK_SIZE]
                await session.execute(
                    delete(UserFavorite).where(
                        UserFavorite.user_id == user_id,
                        tuple_(UserFavorite.source_id, UserFavorite.toon_id).in_(chunk),
                    )
                )

        hist_deletes = [(s, loc) for _, et, s, loc in tombstone_rows if et == "HISTORY"]
        if hist_deletes:
            for offset in range(0, len(hist_deletes), _PREFETCH_CHUNK_SIZE):
                chunk = hist_deletes[offset:offset + _PREFETCH_CHUNK_SIZE]
                await session.execute(
                    delete(UserHistory).where(
                        UserHistory.user_id == user_id,
                        tuple_(UserHistory.source_id, UserHistory.toon_id).in_(chunk),
                    )
                )

        ep_deletes = [(s, loc) for _, et, s, loc in tombstone_rows if et == "EPISODE"]
        if ep_deletes:
            for offset in range(0, len(ep_deletes), _PREFETCH_CHUNK_SIZE):
                chunk = ep_deletes[offset:offset + _PREFETCH_CHUNK_SIZE]
                await session.execute(
                    delete(UserReadEpisode).where(
                        UserReadEpisode.user_id == user_id,
                        tuple_(UserReadEpisode.source_id, UserReadEpisode.toon_id).in_(chunk),
                    )
                )

    # B. Favorites Batch UPSERT (LWW)
    if favorite_rows:
        fav_values = [
            {
                "user_id": user_id,
                "source_id": row_source_id(f),
                "toon_id": favorite_local_id(f),
                "title": f.get("title", ""),
                "thumb_url": f.get("thumbUrl", ""),
                "href": f.get("href", ""),
                "genre": f.get("genre", ""),
                "updated_at": f.get("updatedAt"),
                "saved_at": int(f.get("savedAt", now_ms)),
                "sync_updated_at": now_ms,
            }
            for f in favorite_rows
        ]
        stmt = pg_insert(UserFavorite).values(fav_values)
        stmt = stmt.on_conflict_do_update(
            index_elements=[UserFavorite.user_id, UserFavorite.source_id, UserFavorite.toon_id],
            set_={
                "title": stmt.excluded.title,
                "thumb_url": stmt.excluded.thumb_url,
                "href": stmt.excluded.href,
                "genre": stmt.excluded.genre,
                "updated_at": stmt.excluded.updated_at,
                "saved_at": stmt.excluded.saved_at,
                "sync_updated_at": stmt.excluded.sync_updated_at,
            },
            where=(stmt.excluded.saved_at >= UserFavorite.saved_at),
        )
        await session.execute(stmt)

        # Clear obsolete tombstones for resurrected favorites
        fav_keys = [f"{row_source_id(f)}:{favorite_local_id(f)}" for f in favorite_rows]
        await session.execute(
            delete(UserTombstone).where(
                UserTombstone.user_id == user_id,
                UserTombstone.entity_type == "FAVORITE",
                UserTombstone.entity_id.in_(fav_keys),
                UserTombstone.deleted_at < now_ms,
            )
        )

    # C. History Batch UPSERT (LWW)
    if history_rows:
        hist_values = [
            {
                "user_id": user_id,
                "source_id": row_source_id(h),
                "toon_id": h.get("toonId"),
                "toon_title": h.get("toonTitle", ""),
                "toon_thumb_url": h.get("toonThumbUrl", ""),
                "toon_href": h.get("toonHref", ""),
                "last_wr_id": h.get("lastWrId", ""),
                "last_episode_title": h.get("lastEpisodeTitle", ""),
                "last_episode_href": h.get("lastEpisodeHref", ""),
                "last_read_order": int(h.get("lastReadOrder", 1)),
                "total_episodes": int(h.get("totalEpisodes", 1)),
                "last_read_at": int(h.get("lastReadAt", now_ms)),
                "next_wr_id": h.get("nextWrId"),
                "next_episode_title": h.get("nextEpisodeTitle"),
                "next_episode_href": h.get("nextEpisodeHref"),
                "has_new": bool(h.get("hasNew", False)),
                "sync_updated_at": now_ms,
            }
            for h in history_rows
        ]
        stmt = pg_insert(UserHistory).values(hist_values)
        stmt = stmt.on_conflict_do_update(
            index_elements=[UserHistory.user_id, UserHistory.source_id, UserHistory.toon_id],
            set_={
                "toon_title": stmt.excluded.toon_title,
                "toon_thumb_url": stmt.excluded.toon_thumb_url,
                "toon_href": stmt.excluded.toon_href,
                "last_wr_id": stmt.excluded.last_wr_id,
                "last_episode_title": stmt.excluded.last_episode_title,
                "last_episode_href": stmt.excluded.last_episode_href,
                "last_read_order": stmt.excluded.last_read_order,
                "total_episodes": stmt.excluded.total_episodes,
                "last_read_at": stmt.excluded.last_read_at,
                "next_wr_id": stmt.excluded.next_wr_id,
                "next_episode_title": stmt.excluded.next_episode_title,
                "next_episode_href": stmt.excluded.next_episode_href,
                "has_new": stmt.excluded.has_new,
                "sync_updated_at": stmt.excluded.sync_updated_at,
            },
            where=(
                (stmt.excluded.last_read_order > UserHistory.last_read_order)
                | (
                    (stmt.excluded.last_read_order == UserHistory.last_read_order)
                    & (stmt.excluded.last_read_at >= UserHistory.last_read_at)
                )
                | (stmt.excluded.last_read_at >= UserHistory.last_read_at)
            ),
        )
        await session.execute(stmt)

        # Clear obsolete tombstones for resurrected history
        hist_keys = [f"{row_source_id(h)}:{h.get('toonId')}" for h in history_rows]
        await session.execute(
            delete(UserTombstone).where(
                UserTombstone.user_id == user_id,
                UserTombstone.entity_type == "HISTORY",
                UserTombstone.entity_id.in_(hist_keys),
                UserTombstone.deleted_at < now_ms,
            )
        )

    # D. Read Episodes Batch UPSERT (LWW)
    if episode_rows:
        ep_values = [
            {
                "user_id": user_id,
                "source_id": row_source_id(ep),
                "toon_id": ep.get("toonId"),
                "wr_id": ep.get("wrId"),
                "read_at": int(ep.get("readAt", now_ms)),
                "last_page": int(ep.get("lastPage", 0)),
                "sync_updated_at": now_ms,
            }
            for ep in episode_rows
        ]
        stmt = pg_insert(UserReadEpisode).values(ep_values)
        stmt = stmt.on_conflict_do_update(
            index_elements=[UserReadEpisode.user_id, UserReadEpisode.source_id, UserReadEpisode.toon_id, UserReadEpisode.wr_id],
            set_={
                "read_at": stmt.excluded.read_at,
                "last_page": stmt.excluded.last_page,
                "sync_updated_at": stmt.excluded.sync_updated_at,
            },
            where=(stmt.excluded.read_at >= UserReadEpisode.read_at),
        )
        await session.execute(stmt)

    # E. Reader Settings Batch UPSERT (LWW)
    if setting_rows:
        set_values = [
            {
                "user_id": user_id,
                "source_id": row_source_id(s),
                "toon_id": s.get("toonId"),
                "view_mode": s.get("viewMode", "SINGLE"),
                "read_direction": s.get("readDirection", "RIGHT_TO_LEFT"),
                "split_mode": s.get("splitMode", "FIT"),
                "updated_at": int(s.get("updatedAt", now_ms)),
                "sync_updated_at": now_ms,
            }
            for s in setting_rows
        ]
        stmt = pg_insert(UserReaderSetting).values(set_values)
        stmt = stmt.on_conflict_do_update(
            index_elements=[UserReaderSetting.user_id, UserReaderSetting.source_id, UserReaderSetting.toon_id],
            set_={
                "view_mode": stmt.excluded.view_mode,
                "read_direction": stmt.excluded.read_direction,
                "split_mode": stmt.excluded.split_mode,
                "updated_at": stmt.excluded.updated_at,
                "sync_updated_at": stmt.excluded.sync_updated_at,
            },
            where=(stmt.excluded.updated_at >= UserReaderSetting.updated_at),
        )
        await session.execute(stmt)

    # Upsert global toon_catalog
    catalog_rows = [r for r in changes.get("catalog", []) if accept_inbound_row(r)]
    await upsert_catalog_from_payload(session, history_rows, catalog_rows, now_ms)

    if has_rows:
        await enforce_user_storage_limit(session, user_id, previous_count)
    await session.commit()


# Collect Delta changes since given timestamp
async def collect_delta_for_user(session: AsyncSession, user_id: str, since: int) -> dict[str, Any]:
    # Favorites
    fav_stmt = (
        select(UserFavorite)
        .where(UserFavorite.user_id == user_id, UserFavorite.sync_updated_at > since)
    )
    fav_res = await session.execute(fav_stmt)
    favs = [emit_favorite(f) for f in fav_res.scalars().all()]
    favs = [f for f in favs if f is not None]

    # History
    hist_stmt = (
        select(UserHistory)
        .where(UserHistory.user_id == user_id, UserHistory.sync_updated_at > since)
    )
    hist_res = await session.execute(hist_stmt)
    hist = [emit_history(h) for h in hist_res.scalars().all()]
    hist = [h for h in hist if h is not None]

    # Read Episodes
    ep_stmt = (
        select(UserReadEpisode)
        .where(UserReadEpisode.user_id == user_id, UserReadEpisode.sync_updated_at > since)
    )
    ep_res = await session.execute(ep_stmt)
    eps = [emit_episode(e) for e in ep_res.scalars().all()]
    eps = [e for e in eps if e is not None]

    # Reader Settings
    set_stmt = (
        select(UserReaderSetting)
        .where(UserReaderSetting.user_id == user_id, UserReaderSetting.sync_updated_at > since)
    )
    set_res = await session.execute(set_stmt)
    settings = [emit_setting(s) for s in set_res.scalars().all()]
    settings = [s for s in settings if s is not None]

    # Tombstones
    tomb_stmt = (
        select(UserTombstone)
        .where(UserTombstone.user_id == user_id, UserTombstone.deleted_at > since)
    )
    tomb_res = await session.execute(tomb_stmt)
    tombs = [emit_tombstone(t) for t in tomb_res.scalars().all()]
    tombs = [t for t in tombs if t is not None]

    # Toon Catalog (global crowdsourced counts)
    cat_stmt = (
        select(ToonCatalog)
        .where(ToonCatalog.updated_at > since)
        .order_by(ToonCatalog.updated_at.desc())
        .limit(500)
    )
    cat_res = await session.execute(cat_stmt)
    catalog = [emit_catalog(c) for c in cat_res.scalars().all()]

    return {
        "favorites": favs,
        "history": hist,
        "readEpisodes": eps,
        "readerSettings": settings,
        "tombstones": tombs,
        "catalog": catalog,
    }


# =============================================================================
# ENDPOINTS
# =============================================================================

@router.get("/sync")
@router.get("/api/comics8/sync")
async def sync_pull(
    identity: SyncIdentity = Depends(get_sync_user),
    session: AsyncSession = Depends(get_db),
):
    snapshot = await export_snapshot_for_user(session, identity.user_id)
    return JSONResponse(status_code=200, content=snapshot)


@router.post("/sync")
@router.post("/api/comics8/sync")
async def sync_push_snapshot(
    request: Request,
    identity: SyncIdentity = Depends(get_sync_user),
    session: AsyncSession = Depends(get_db),
):
    now_ms = int(time.time() * 1000)

    data = await read_bounded_json(request)
    validate_sync_payload(data)

    await import_snapshot_for_user(session, identity.user_id, data, now_ms, identity=identity)
    is_pro = await check_user_is_pro(session, identity.user_id)

    return {
        "status": "success",
        "savedAt": now_ms,
        "userId": identity.user_id,
        "sizeBytes": request.state.sync_body_bytes,
        "favorites": len(data.get("favorites", [])),
        "history": len(data.get("history", [])),
        "isPro": is_pro,
    }


class DeltaSyncRequest(BaseModel):
    since: int = 0
    clientTime: int | None = None
    version: int = 2
    changes: dict[str, Any] = Field(default_factory=dict)


@router.post("/sync/delta")
@router.post("/api/comics8/sync/delta")
async def sync_delta(
    request: Request,
    identity: SyncIdentity = Depends(get_sync_user),
    session: AsyncSession = Depends(get_db),
):
    now_ms = int(time.time() * 1000)

    try:
        raw_payload = await read_bounded_json(request)
        try:
            payload = DeltaSyncRequest.model_validate(raw_payload)
        except ValidationError as exc:
            raise HTTPException(status_code=400, detail=f"Invalid delta payload: {exc}")
        validate_sync_payload(payload.changes)
        await apply_delta_for_user(
            session, identity.user_id, payload.changes, now_ms, identity=identity
        )
        server_changes = await collect_delta_for_user(session, identity.user_id, payload.since)
        is_pro = await check_user_is_pro(session, identity.user_id)

        return {
            "status": "success",
            "serverTime": now_ms,
            "userId": identity.user_id,
            "changes": server_changes,
            "isPro": is_pro,
        }
    except HTTPException:
        raise
    except Exception as e:
        logger.error(f"Delta sync error for user {identity.user_id}: {e}")
        raise HTTPException(
            status_code=400,
            detail={"error": "DeltaSyncError", "message": "Invalid delta sync payload"},
        )


def get_client_ip(request: Request) -> str:
    cf_ip = request.headers.get("cf-connecting-ip")
    if cf_ip and cf_ip.strip():
        return cf_ip.strip()
    xff = request.headers.get("x-forwarded-for")
    if xff and xff.strip():
        return xff.split(",")[0].strip()
    if request.client and request.client.host:
        return request.client.host
    return "unknown"


_PAIR_RATE_CLIENT_CAPACITY = 10_000
_pair_confirm_attempts: OrderedDict[str, deque[float]] = OrderedDict()
_pair_confirm_attempt_lock = asyncio.Lock()


async def check_pair_confirm_rate_limit(client_id: str) -> None:
    settings = get_settings()
    limit = settings.PAIR_CONFIRM_MAX_ATTEMPTS
    window = settings.PAIR_CONFIRM_WINDOW_SECONDS
    if limit <= 0 or window <= 0:
        return

    now = time.monotonic()
    cutoff = now - window
    async with _pair_confirm_attempt_lock:
        attempts = _pair_confirm_attempts.pop(client_id, deque())
        while attempts and attempts[0] <= cutoff:
            attempts.popleft()
        if len(attempts) >= limit:
            _pair_confirm_attempts[client_id] = attempts
            retry_after = max(1, int(attempts[0] + window - now) + 1)
            raise HTTPException(
                status_code=status.HTTP_429_TOO_MANY_REQUESTS,
                detail={"success": False, "message": "페어링 시도가 너무 많습니다. 잠시 후 다시 시도해주세요."},
                headers={"Retry-After": str(retry_after)},
            )
        attempts.append(now)
        _pair_confirm_attempts[client_id] = attempts
        if len(_pair_confirm_attempts) > _PAIR_RATE_CLIENT_CAPACITY:
            _pair_confirm_attempts.popitem(last=False)


@router.post("/pair/request")
@router.post("/api/comics8/pair/request")
async def pair_request(
    identity: SyncIdentity = Depends(get_existing_sync_user),
    session: AsyncSession = Depends(get_db),
):
    sync_key = identity.sync_key
    settings = get_settings()
    ttl = settings.PAIR_CODE_TTL_SECONDS
    now_dt = datetime.now(timezone.utc)
    exp_dt = datetime.fromtimestamp(now_dt.timestamp() + ttl, tz=timezone.utc)

    await session.execute(delete(PairingCode).where(PairingCode.expires_at <= now_dt))
    await session.execute(delete(PairingCode).where(PairingCode.sync_key == sync_key))

    # Generate 6-digit code
    for _ in range(10):
        code = f"{100000 + secrets.randbelow(900000)}"
        if await session.get(PairingCode, code) is None:
            break
    else:
        raise HTTPException(status_code=503, detail="Unable to allocate pairing code")

    session.add(PairingCode(code=code, sync_key=sync_key, expires_at=exp_dt))
    await session.commit()

    return {
        "success": True,
        "code": code,
        "expiresInSeconds": ttl,
    }


class PairConfirmRequest(BaseModel):
    code: str


@router.post("/pair/confirm")
@router.post("/api/comics8/pair/confirm")
async def pair_confirm(
    request: Request,
    payload: PairConfirmRequest,
    session: AsyncSession = Depends(get_db),
):
    code = payload.code.strip().replace(" ", "").replace("-", "")
    if not re.fullmatch(r"\d{6}", code):
        raise HTTPException(
            status_code=400,
            detail={"success": False, "message": "6자리 숫자 페어링 코드를 입력해주세요."},
        )

    client_id = get_client_ip(request)
    await check_pair_confirm_rate_limit(client_id)

    now_dt = datetime.now(timezone.utc)
    stmt = (
        delete(PairingCode)
        .where(PairingCode.code == code, PairingCode.expires_at > now_dt)
        .returning(PairingCode.sync_key)
    )
    res = await session.execute(stmt)
    sync_key = res.scalar_one_or_none()

    if not sync_key:
        raise HTTPException(
            status_code=400,
            detail={"success": False, "message": "유효하지 않거나 만료된 페어링 코드입니다."},
        )

    await session.commit()

    return {
        "success": True,
        "syncKey": sync_key,
    }


_CATALOG_RATE_CLIENT_CAPACITY = 10_000
_catalog_rate_attempts: OrderedDict[str, deque[float]] = OrderedDict()
_catalog_rate_attempt_lock = asyncio.Lock()


async def check_catalog_rate_limit(client_id: str) -> None:
    settings = get_settings()
    limit = settings.CATALOG_RATE_LIMIT_PER_MINUTE
    window = 60.0
    if limit <= 0:
        return

    now = time.monotonic()
    cutoff = now - window
    async with _catalog_rate_attempt_lock:
        attempts = _catalog_rate_attempts.pop(client_id, deque())
        while attempts and attempts[0] <= cutoff:
            attempts.popleft()
        if len(attempts) >= limit:
            _catalog_rate_attempts[client_id] = attempts
            retry_after = max(1, int(attempts[0] + window - now) + 1)
            raise HTTPException(
                status_code=status.HTTP_429_TOO_MANY_REQUESTS,
                detail={"error": "TooManyRequests", "message": "카탈로그 요청 한도를 초과했습니다. 잠시 후 다시 시도해주세요."},
                headers={"Retry-After": str(retry_after)},
            )
        attempts.append(now)
        _catalog_rate_attempts[client_id] = attempts
        if len(_catalog_rate_attempts) > _CATALOG_RATE_CLIENT_CAPACITY:
            _catalog_rate_attempts.popitem(last=False)


class CatalogBatchRequest(BaseModel):
    items: list[dict[str, Any]] = Field(default_factory=list)
    updates: list[dict[str, Any]] = Field(default_factory=list)


@router.post("/catalog/batch")
@router.post("/api/comics8/catalog/batch")
async def get_catalog_batch(
    request: Request,
    req: CatalogBatchRequest,
    session: AsyncSession = Depends(get_db),
):
    client_id = get_client_ip(request)
    await check_catalog_rate_limit(client_id)

    now_ms = int(time.time() * 1000)

    # 1. Process crowdsourced updates if present
    if req.updates:
        catalog_rows = [r for r in req.updates[:200] if accept_inbound_row(r)]
        if catalog_rows:
            await upsert_catalog_from_payload(session, [], catalog_rows, now_ms)
            await session.commit()

    # 2. Query requested items
    if not req.items:
        return {"catalog": []}

    conditions = []
    for item in req.items[:200]:
        s_id = row_source_id(item)
        t_id = str(item.get("toonId") or item.get("id") or "").strip()
        if t_id:
            conditions.append((s_id, t_id))

    if not conditions:
        return {"catalog": []}

    stmt = select(ToonCatalog).where(
        tuple_(ToonCatalog.source_id, ToonCatalog.toon_id).in_(conditions)
    )
    res = await session.execute(stmt)
    records = res.scalars().all()
    return {"catalog": [emit_catalog(r) for r in records]}


@router.post("/catalog/report")
@router.post("/api/comics8/catalog/report")
async def report_catalog(
    request: Request,
    req: CatalogBatchRequest,
    session: AsyncSession = Depends(get_db),
):
    client_id = get_client_ip(request)
    await check_catalog_rate_limit(client_id)

    updates = req.updates or req.items
    if updates:
        catalog_rows = [r for r in updates[:200] if accept_inbound_row(r)]
        if catalog_rows:
            now_ms = int(time.time() * 1000)
            await upsert_catalog_from_payload(session, [], catalog_rows, now_ms)
            await session.commit()
            return {"status": "ok", "updated": len(catalog_rows)}
    return {"status": "ok", "updated": 0}


class SourceResolveRequest(BaseModel):
    client_source_id: str = Field(..., max_length=255)
    origin_host: str = Field(default="", max_length=255)
    sample_toon_ids: list[str] = Field(default_factory=list)
    sample_titles: list[str] = Field(default_factory=list)


class SourceResolveResponse(BaseModel):
    canonical_source_id: str
    status: str  # "MATCHED_HOST", "MATCHED_IDS", "MATCHED_TITLE", "CREATED_NEW"
    confidence: float
    reason: str = ""


def _detect_toon_id_pattern(sample_ids: list[str]) -> str:
    if not sample_ids:
        return ".*"
    if all(re.match(r"^\d+$", i) for i in sample_ids):
        return r"^\d+$"
    if all(re.match(r"^artist:[^:]+$", i) for i in sample_ids):
        return r"^artist:.+$"
    if all(re.match(r"^[a-zA-Z0-9%_-]+$", i) for i in sample_ids):
        return r"^[a-zA-Z0-9%_-]+$"
    return ".*"


_DEFAULT_SOURCE_SEEDS: dict[str, dict[str, Any]] = {
    "eleven": {
        "display_name": "11toon",
        "primary_host": "11toon.com",
        "known_hosts": [
            "11toon.com",
            "update.spotv24.com",
            "spotv24.com",
            "103.204.13.68",
            "fu01.11toon24.com",
            "update.11toon24.com",
            "11toon24.com",
            "11toon8.com",
        ],
        "id_pattern": r"^\d+$",
    },
    "hitomi": {
        "display_name": "Hitomi",
        "primary_host": "hitomi.la",
        "known_hosts": ["hitomi.la"],
        "id_pattern": r"^artist:.+$",
    },
    "rawkuma": {
        "display_name": "Rawkuma",
        "primary_host": "rawkuma.net",
        "known_hosts": ["rawkuma.net"],
        "id_pattern": r"^[a-zA-Z0-9%_-]+$",
    },
}


async def resolve_source_identity(
    session: AsyncSession,
    req: SourceResolveRequest,
) -> dict[str, Any]:
    client_source_id = req.client_source_id.strip()
    raw_host = (req.origin_host or "").strip().lower()
    if "://" in raw_host:
        try:
            raw_host = urlparse(raw_host).hostname or raw_host
        except Exception:
            pass
    raw_host = raw_host.strip("/")
    # Strip port if present (e.g. "103.204.13.68:8904" -> "103.204.13.68")
    origin_host = raw_host
    origin_host_clean = origin_host.split(":")[0] if ":" in origin_host else origin_host
    sample_ids = [str(x).strip() for x in req.sample_toon_ids[:100] if str(x).strip()]
    sample_titles = [str(x).strip() for x in req.sample_titles[:100] if str(x).strip()]
    now_ms = int(time.time() * 1000)

    # Tier 1: Host / Mirror Match (O(1))
    if origin_host:
        # Check in-memory well-known seeds first
        for seed_id, seed_data in _DEFAULT_SOURCE_SEEDS.items():
            hosts = seed_data.get("known_hosts", [])
            if seed_data.get("primary_host") in (origin_host, origin_host_clean) or origin_host in hosts or origin_host_clean in hosts:
                return {
                    "canonical_source_id": seed_id,
                    "status": "MATCHED_HOST",
                    "confidence": 1.0,
                    "reason": f"도메인 '{origin_host}' 일치 (사전 등록 도메인)",
                }

        stmt = select(ToonSource)
        res = await session.execute(stmt)
        sources = res.scalars().all()
        for src in sources:
            hosts = src.known_hosts if isinstance(src.known_hosts, list) else []
            if src.primary_host in (origin_host, origin_host_clean) or origin_host in hosts or origin_host_clean in hosts:
                return {
                    "canonical_source_id": src.id,
                    "status": "MATCHED_HOST",
                    "confidence": 1.0,
                    "reason": f"도메인 '{origin_host}' 일치",
                }

    # Tier 2: Cumulative Toon ID Intersection (누적 카탈로그 대조)
    if sample_ids:
        stmt = (
            select(ToonCatalog.source_id, func.count().label("cnt"))
            .where(ToonCatalog.toon_id.in_(sample_ids))
            .group_by(ToonCatalog.source_id)
            .order_by(func.count().desc())
            .limit(1)
        )
        res = await session.execute(stmt)
        row = res.first()
        if row:
            matched_source, count = row[0], int(row[1])
            threshold = max(2, int(len(sample_ids) * 0.15))
            if count >= threshold:
                # Self-healing domain tracking
                if origin_host:
                    src_stmt = select(ToonSource).where(ToonSource.id == matched_source)
                    src_res = await session.execute(src_stmt)
                    src = src_res.scalar_one_or_none()
                    if src:
                        hosts = list(src.known_hosts or [])
                        if origin_host not in hosts:
                            hosts.append(origin_host)
                            src.known_hosts = hosts
                            src.updated_at = now_ms
                            await session.commit()

                return {
                    "canonical_source_id": matched_source,
                    "status": "MATCHED_IDS",
                    "confidence": round(count / len(sample_ids), 2),
                    "reason": f"{len(sample_ids)}개 중 {count}개 ID 일치",
                }

    # Tier 3: Title Semantic Fallback (1달 뒤 50개가 전면 물갈이된 엣지 케이스)
    if sample_titles:
        clean_titles = [t.lower().replace(" ", "") for t in sample_titles[:50]]
        clean_titles = [t for t in clean_titles if t]
        if clean_titles:
            title_stmt = (
                select(UserHistory.source_id, func.count().label("cnt"))
                .where(func.lower(func.replace(UserHistory.toon_title, " ", "")).in_(clean_titles))
                .group_by(UserHistory.source_id)
                .order_by(func.count().desc())
                .limit(1)
            )
            title_res = await session.execute(title_stmt)
            t_row = title_res.first()
            if t_row and int(t_row[1]) >= 2:
                matched_source = t_row[0]
                return {
                    "canonical_source_id": matched_source,
                    "status": "MATCHED_TITLE",
                    "confidence": 0.8,
                    "reason": f"제목 {t_row[1]}개 일치 (물갈이 대응 Fallback)",
                }

    # Tier 4: New Source Creation (새로운 사이트)
    clean_id = re.sub(r"[^a-zA-Z0-9._-]", "", client_source_id).strip() or "source"
    check_stmt = select(ToonSource).where(ToonSource.id == clean_id)
    exists = (await session.execute(check_stmt)).scalar_one_or_none()
    new_id = clean_id if not exists else f"{clean_id}_{secrets.token_hex(3)}"

    pattern = _detect_toon_id_pattern(sample_ids)
    new_src = ToonSource(
        id=new_id,
        display_name=client_source_id,
        primary_host=origin_host or None,
        known_hosts=[origin_host] if origin_host else [],
        id_pattern=pattern,
        created_at=now_ms,
        updated_at=now_ms,
    )
    session.add(new_src)
    await session.commit()

    return {
        "canonical_source_id": new_id,
        "status": "CREATED_NEW",
        "confidence": 1.0,
        "reason": "신규 소스 등록",
    }


@router.post("/catalog/source/resolve")
@router.post("/api/comics8/catalog/source/resolve")
async def resolve_catalog_source(
    request: Request,
    req: SourceResolveRequest,
    session: AsyncSession = Depends(get_db),
):
    client_id = get_client_ip(request)
    await check_catalog_rate_limit(client_id)
    return await resolve_source_identity(session, req)

