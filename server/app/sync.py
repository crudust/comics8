from __future__ import annotations

import hashlib
import logging
import random
import re
import time
from datetime import datetime, timezone
from typing import Any
from fastapi import APIRouter, Depends, Header, HTTPException, Query, Request, Response, status
from fastapi.responses import JSONResponse
from pydantic import BaseModel, Field
from sqlalchemy import delete, func, select, tuple_
from sqlalchemy.ext.asyncio import AsyncSession
from app.config import get_settings
from app.database import get_db
from app.models import (
    PairingCode,
    Subscription,
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


async def _prefetch_entities(
    session: AsyncSession,
    model: type,
    user_id: str,
    key_columns: tuple,
    keys: set[tuple],
) -> dict[tuple, Any]:
    """Load request-scoped entities in bounded batches instead of one query per row."""
    if not keys:
        return {}

    ordered_keys = list(keys)
    entities: dict[tuple, Any] = {}
    for offset in range(0, len(ordered_keys), _PREFETCH_CHUNK_SIZE):
        chunk = ordered_keys[offset:offset + _PREFETCH_CHUNK_SIZE]
        stmt = select(model).where(
            model.user_id == user_id,
            tuple_(*key_columns).in_(chunk),
        )
        result = await session.execute(stmt)
        for entity in result.scalars():
            entities[tuple(getattr(entity, column.key) for column in key_columns)] = entity
    return entities


def get_safe_user_id(key: str) -> str:
    cleaned = re.sub(r"[^a-zA-Z0-9_\-]", "", key.strip())
    if 3 <= len(cleaned) <= 64:
        return cleaned
    return hashlib.sha256(key.strip().encode("utf-8")).hexdigest()[:24]


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


# Dependency for extracting and ensuring user
async def get_sync_user(
    request: Request,
    authorization: str | None = Header(None),
    x_sync_key: str | None = Header(None, alias="X-Sync-Key"),
    key: str | None = Query(None),
    token: str | None = Query(None),
    session: AsyncSession = Depends(get_db),
) -> tuple[str, str]:
    raw_key = None
    if authorization and authorization.startswith("Bearer "):
        raw_key = authorization[7:].strip()
    elif x_sync_key:
        raw_key = x_sync_key.strip()
    elif key:
        raw_key = key.strip()
    elif token:
        raw_key = token.strip()

    if not raw_key:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail={"error": "Unauthorized", "message": "Missing sync key"},
        )

    user_id = get_safe_user_id(raw_key)
    user = await session.get(User, user_id)
    if user is None:
        user = User(user_id=user_id, sync_key=raw_key)
        session.add(user)
        await session.commit()
    elif user.sync_key != raw_key:
        user.sync_key = raw_key
        await session.commit()

    return user_id, raw_key


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


# Import full snapshot
async def import_snapshot_for_user(
    session: AsyncSession,
    user_id: str,
    data: dict[str, Any],
    now_ms: int,
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

    favorites = await _prefetch_entities(
        session, UserFavorite, user_id,
        (UserFavorite.source_id, UserFavorite.toon_id),
        {(row_source_id(f), favorite_local_id(f)) for f in favorite_rows},
    )
    histories = await _prefetch_entities(
        session, UserHistory, user_id,
        (UserHistory.source_id, UserHistory.toon_id),
        {(row_source_id(h), h["toonId"]) for h in history_rows},
    )
    episodes = await _prefetch_entities(
        session, UserReadEpisode, user_id,
        (UserReadEpisode.source_id, UserReadEpisode.toon_id, UserReadEpisode.wr_id),
        {(row_source_id(ep), ep["toonId"], ep["wrId"]) for ep in episode_rows},
    )
    settings = await _prefetch_entities(
        session, UserReaderSetting, user_id,
        (UserReaderSetting.source_id, UserReaderSetting.toon_id),
        {(row_source_id(s), s["toonId"]) for s in setting_rows},
    )

    # 1. Favorites
    for f in favorite_rows:
        source_id = row_source_id(f)
        toon_id = favorite_local_id(f)
        saved_at = int(f.get("savedAt") or now_ms)
        key = (source_id, toon_id)
        fav = favorites.get(key)
        if fav is None:
            fav = UserFavorite(
                user_id=user_id,
                source_id=source_id,
                toon_id=toon_id,
                title=f.get("title", ""),
                thumb_url=f.get("thumbUrl", ""),
                href=f.get("href", ""),
                genre=f.get("genre", ""),
                updated_at=f.get("updatedAt"),
                saved_at=saved_at,
                sync_updated_at=now_ms,
            )
            session.add(fav)
            favorites[key] = fav
        else:
            fav.title = f.get("title", "")
            fav.thumb_url = f.get("thumbUrl", "")
            fav.href = f.get("href", "")
            fav.genre = f.get("genre", "")
            fav.updated_at = f.get("updatedAt")
            fav.saved_at = saved_at
            fav.sync_updated_at = now_ms

    # 2. History
    for h in history_rows:
        source_id = row_source_id(h)
        toon_id = h.get("toonId")
        last_read_at = int(h.get("lastReadAt") or now_ms)
        key = (source_id, toon_id)
        hist = histories.get(key)
        if hist is None:
            hist = UserHistory(
                user_id=user_id,
                source_id=source_id,
                toon_id=toon_id,
                toon_title=h.get("toonTitle", ""),
                toon_thumb_url=h.get("toonThumbUrl", ""),
                toon_href=h.get("toonHref", ""),
                last_wr_id=h.get("lastWrId", ""),
                last_episode_title=h.get("lastEpisodeTitle", ""),
                last_episode_href=h.get("lastEpisodeHref", ""),
                last_read_order=int(h.get("lastReadOrder", 1)),
                total_episodes=int(h.get("totalEpisodes", 1)),
                last_read_at=last_read_at,
                next_wr_id=h.get("nextWrId"),
                next_episode_title=h.get("nextEpisodeTitle"),
                next_episode_href=h.get("nextEpisodeHref"),
                has_new=bool(h.get("hasNew", False)),
                sync_updated_at=now_ms,
            )
            session.add(hist)
            histories[key] = hist
        else:
            hist.toon_title = h.get("toonTitle", "")
            hist.toon_thumb_url = h.get("toonThumbUrl", "")
            hist.toon_href = h.get("toonHref", "")
            hist.last_wr_id = h.get("lastWrId", "")
            hist.last_episode_title = h.get("lastEpisodeTitle", "")
            hist.last_episode_href = h.get("lastEpisodeHref", "")
            hist.last_read_order = int(h.get("lastReadOrder", 1))
            hist.total_episodes = int(h.get("totalEpisodes", 1))
            hist.last_read_at = last_read_at
            hist.next_wr_id = h.get("nextWrId")
            hist.next_episode_title = h.get("nextEpisodeTitle")
            hist.next_episode_href = h.get("nextEpisodeHref")
            hist.has_new = bool(h.get("hasNew", False))
            hist.sync_updated_at = now_ms

    # 3. Read Episodes
    for ep in episode_rows:
        source_id = row_source_id(ep)
        toon_id = ep.get("toonId")
        wr_id = ep.get("wrId")
        read_at = int(ep.get("readAt") or now_ms)
        key = (source_id, toon_id, wr_id)
        read_ep = episodes.get(key)
        if read_ep is None:
            read_ep = UserReadEpisode(
                user_id=user_id,
                source_id=source_id,
                toon_id=toon_id,
                wr_id=wr_id,
                read_at=read_at,
                last_page=int(ep.get("lastPage", 0)),
                sync_updated_at=now_ms,
            )
            session.add(read_ep)
            episodes[key] = read_ep
        else:
            read_ep.read_at = read_at
            read_ep.last_page = int(ep.get("lastPage", 0))
            read_ep.sync_updated_at = now_ms

    # 4. Reader Settings
    for s in setting_rows:
        source_id = row_source_id(s)
        toon_id = s.get("toonId")
        up_at = int(s.get("updatedAt") or now_ms)
        key = (source_id, toon_id)
        setting = settings.get(key)
        if setting is None:
            setting = UserReaderSetting(
                user_id=user_id,
                source_id=source_id,
                toon_id=toon_id,
                view_mode=s.get("viewMode", "SINGLE"),
                read_direction=s.get("readDirection", "RIGHT_TO_LEFT"),
                split_mode=s.get("splitMode", "FIT"),
                updated_at=up_at,
                sync_updated_at=now_ms,
            )
            session.add(setting)
            settings[key] = setting
        else:
            setting.view_mode = s.get("viewMode", "SINGLE")
            setting.read_direction = s.get("readDirection", "RIGHT_TO_LEFT")
            setting.split_mode = s.get("splitMode", "FIT")
            setting.updated_at = up_at
            setting.sync_updated_at = now_ms

    await session.commit()


# Apply Delta changes
async def apply_delta_for_user(
    session: AsyncSession,
    user_id: str,
    changes: dict[str, Any],
    now_ms: int,
) -> None:
    tombstone_rows: list[tuple[dict[str, Any], str, str, str]] = []
    for t in changes.get("tombstones", []):
        entity_type = (t.get("entityType") or "").upper()
        source_id, local_id = parse_entity_id(t.get("entityId", ""))
        if local_id and source_id != "local":
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

    live_tombstone_keys = {
        ("FAVORITE", f"{row_source_id(f)}:{favorite_local_id(f)}") for f in favorite_rows
    } | {
        ("HISTORY", f"{row_source_id(h)}:{h['toonId']}") for h in history_rows
    }
    all_tombstone_keys = live_tombstone_keys | {
        (entity_type, f"{source_id}:{local_id}")
        for _, entity_type, source_id, local_id in tombstone_rows
    }
    tombstones = await _prefetch_entities(
        session, UserTombstone, user_id,
        (UserTombstone.entity_type, UserTombstone.entity_id),
        all_tombstone_keys,
    )
    favorites = await _prefetch_entities(
        session, UserFavorite, user_id,
        (UserFavorite.source_id, UserFavorite.toon_id),
        {(row_source_id(f), favorite_local_id(f)) for f in favorite_rows},
    )
    histories = await _prefetch_entities(
        session, UserHistory, user_id,
        (UserHistory.source_id, UserHistory.toon_id),
        {(row_source_id(h), h["toonId"]) for h in history_rows},
    )
    episodes = await _prefetch_entities(
        session, UserReadEpisode, user_id,
        (UserReadEpisode.source_id, UserReadEpisode.toon_id, UserReadEpisode.wr_id),
        {(row_source_id(ep), ep["toonId"], ep["wrId"]) for ep in episode_rows},
    )
    settings = await _prefetch_entities(
        session, UserReaderSetting, user_id,
        (UserReaderSetting.source_id, UserReaderSetting.toon_id),
        {(row_source_id(s), s["toonId"]) for s in setting_rows},
    )

    # A. Tombstones
    delete_keys: dict[str, set[tuple[str, str]]] = {
        "FAVORITE": set(),
        "HISTORY": set(),
        "EPISODE": set(),
    }
    for t, e_type, source_id, local_id in tombstone_rows:
        del_at = int(t.get("deletedAt", now_ms))
        stored_id = f"{source_id}:{local_id}"
        tombstone_key = (e_type, stored_id)

        # Upsert tombstone
        tomb = tombstones.get(tombstone_key)
        if tomb is None:
            tomb = UserTombstone(
                user_id=user_id,
                entity_type=e_type,
                entity_id=stored_id,
                deleted_at=del_at,
            )
            session.add(tomb)
            tombstones[tombstone_key] = tomb
        else:
            tomb.deleted_at = max(tomb.deleted_at, del_at)

        if e_type in delete_keys:
            delete_keys[e_type].add((source_id, local_id))

    delete_specs = (
        ("FAVORITE", UserFavorite),
        ("HISTORY", UserHistory),
        ("EPISODE", UserReadEpisode),
    )
    for entity_type, model in delete_specs:
        keys = delete_keys[entity_type]
        ordered_keys = list(keys)
        for offset in range(0, len(ordered_keys), _PREFETCH_CHUNK_SIZE):
            chunk = ordered_keys[offset:offset + _PREFETCH_CHUNK_SIZE]
            await session.execute(
                delete(model).where(
                    model.user_id == user_id,
                    tuple_(model.source_id, model.toon_id).in_(chunk),
                )
            )
    for key in delete_keys["FAVORITE"]:
        favorites.pop(key, None)
    for key in delete_keys["HISTORY"]:
        histories.pop(key, None)
    if delete_keys["EPISODE"]:
        episodes = {
            key: value for key, value in episodes.items()
            if key[:2] not in delete_keys["EPISODE"]
        }

    # B. Favorites
    for f in favorite_rows:
        source_id = row_source_id(f)
        f_id = favorite_local_id(f)
        saved_at = int(f.get("savedAt", now_ms))
        key = (source_id, f_id)
        fav = favorites.get(key)
        if fav is None:
            fav = UserFavorite(
                user_id=user_id,
                source_id=source_id,
                toon_id=f_id,
                title=f.get("title", ""),
                thumb_url=f.get("thumbUrl", ""),
                href=f.get("href", ""),
                genre=f.get("genre", ""),
                updated_at=f.get("updatedAt"),
                saved_at=saved_at,
                sync_updated_at=now_ms,
            )
            session.add(fav)
            favorites[key] = fav
        elif saved_at >= fav.saved_at:
            fav.title = f.get("title", "")
            fav.thumb_url = f.get("thumbUrl", "")
            fav.href = f.get("href", "")
            fav.genre = f.get("genre", "")
            fav.updated_at = f.get("updatedAt")
            fav.saved_at = saved_at
            fav.sync_updated_at = now_ms

        # Delete older tombstone if re-added
        stored_id = f"{source_id}:{f_id}"
        tombstone_key = ("FAVORITE", stored_id)
        tomb = tombstones.get(tombstone_key)
        if tomb is not None and tomb.deleted_at < saved_at:
            await session.delete(tomb)
            tombstones.pop(tombstone_key, None)

    # C. History
    for h in history_rows:
        source_id = row_source_id(h)
        toon_id = h.get("toonId")
        last_read_at = int(h.get("lastReadAt", now_ms))
        key = (source_id, toon_id)
        hist = histories.get(key)
        if hist is None:
            hist = UserHistory(
                user_id=user_id,
                source_id=source_id,
                toon_id=toon_id,
                toon_title=h.get("toonTitle", ""),
                toon_thumb_url=h.get("toonThumbUrl", ""),
                toon_href=h.get("toonHref", ""),
                last_wr_id=h.get("lastWrId", ""),
                last_episode_title=h.get("lastEpisodeTitle", ""),
                last_episode_href=h.get("lastEpisodeHref", ""),
                last_read_order=int(h.get("lastReadOrder", 1)),
                total_episodes=int(h.get("totalEpisodes", 1)),
                last_read_at=last_read_at,
                next_wr_id=h.get("nextWrId"),
                next_episode_title=h.get("nextEpisodeTitle"),
                next_episode_href=h.get("nextEpisodeHref"),
                has_new=bool(h.get("hasNew", False)),
                sync_updated_at=now_ms,
            )
            session.add(hist)
            histories[key] = hist
        elif last_read_at >= hist.last_read_at:
            hist.toon_title = h.get("toonTitle", "")
            hist.toon_thumb_url = h.get("toonThumbUrl", "")
            hist.toon_href = h.get("toonHref", "")
            hist.last_wr_id = h.get("lastWrId", "")
            hist.last_episode_title = h.get("lastEpisodeTitle", "")
            hist.last_episode_href = h.get("lastEpisodeHref", "")
            hist.last_read_order = int(h.get("lastReadOrder", 1))
            hist.total_episodes = int(h.get("totalEpisodes", 1))
            hist.last_read_at = last_read_at
            hist.next_wr_id = h.get("nextWrId")
            hist.next_episode_title = h.get("nextEpisodeTitle")
            hist.next_episode_href = h.get("nextEpisodeHref")
            hist.has_new = bool(h.get("hasNew", False))
            hist.sync_updated_at = now_ms

        stored_id = f"{source_id}:{toon_id}"
        tombstone_key = ("HISTORY", stored_id)
        tomb = tombstones.get(tombstone_key)
        if tomb is not None and tomb.deleted_at < last_read_at:
            await session.delete(tomb)
            tombstones.pop(tombstone_key, None)

    # D. Read Episodes
    for ep in episode_rows:
        source_id = row_source_id(ep)
        toon_id = ep.get("toonId")
        wr_id = ep.get("wrId")
        read_at = int(ep.get("readAt", now_ms))
        key = (source_id, toon_id, wr_id)
        read_ep = episodes.get(key)
        if read_ep is None:
            read_ep = UserReadEpisode(
                user_id=user_id,
                source_id=source_id,
                toon_id=toon_id,
                wr_id=wr_id,
                read_at=read_at,
                last_page=int(ep.get("lastPage", 0)),
                sync_updated_at=now_ms,
            )
            session.add(read_ep)
            episodes[key] = read_ep
        elif read_at >= read_ep.read_at:
            read_ep.read_at = read_at
            read_ep.last_page = int(ep.get("lastPage", 0))
            read_ep.sync_updated_at = now_ms

    # E. Reader Settings
    for s in setting_rows:
        source_id = row_source_id(s)
        toon_id = s.get("toonId")
        up_at = int(s.get("updatedAt", now_ms))
        key = (source_id, toon_id)
        setting = settings.get(key)
        if setting is None:
            setting = UserReaderSetting(
                user_id=user_id,
                source_id=source_id,
                toon_id=toon_id,
                view_mode=s.get("viewMode", "SINGLE"),
                read_direction=s.get("readDirection", "RIGHT_TO_LEFT"),
                split_mode=s.get("splitMode", "FIT"),
                updated_at=up_at,
                sync_updated_at=now_ms,
            )
            session.add(setting)
            settings[key] = setting
        elif up_at >= setting.updated_at:
            setting.view_mode = s.get("viewMode", "SINGLE")
            setting.read_direction = s.get("readDirection", "RIGHT_TO_LEFT")
            setting.split_mode = s.get("splitMode", "FIT")
            setting.updated_at = up_at
            setting.sync_updated_at = now_ms

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

    return {
        "favorites": favs,
        "history": hist,
        "readEpisodes": eps,
        "readerSettings": settings,
        "tombstones": tombs,
    }


# =============================================================================
# ENDPOINTS
# =============================================================================

@router.get("/sync")
@router.get("/api/comics8/sync")
async def sync_pull(
    user_info: tuple[str, str] = Depends(get_sync_user),
    session: AsyncSession = Depends(get_db),
):
    user_id, _ = user_info
    snapshot = await export_snapshot_for_user(session, user_id)
    return JSONResponse(status_code=200, content=snapshot)


@router.post("/sync")
@router.post("/api/comics8/sync")
async def sync_push_snapshot(
    request: Request,
    user_info: tuple[str, str] = Depends(get_sync_user),
    session: AsyncSession = Depends(get_db),
):
    user_id, _ = user_info
    now_ms = int(time.time() * 1000)

    try:
        body_bytes = await request.body()
        if not body_bytes:
            raise HTTPException(status_code=400, detail={"error": "BadRequest", "message": "Empty body"})
        data = await request.json()
    except Exception as e:
        raise HTTPException(status_code=400, detail={"error": "BadRequest", "message": str(e)})

    await import_snapshot_for_user(session, user_id, data, now_ms)
    is_pro = await check_user_is_pro(session, user_id)

    return {
        "status": "success",
        "savedAt": now_ms,
        "userId": user_id,
        "sizeBytes": len(body_bytes),
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
    payload: DeltaSyncRequest,
    user_info: tuple[str, str] = Depends(get_sync_user),
    session: AsyncSession = Depends(get_db),
):
    user_id, _ = user_info
    now_ms = int(time.time() * 1000)

    try:
        await apply_delta_for_user(session, user_id, payload.changes, now_ms)
        server_changes = await collect_delta_for_user(session, user_id, payload.since)
        is_pro = await check_user_is_pro(session, user_id)

        return {
            "status": "success",
            "serverTime": now_ms,
            "userId": user_id,
            "changes": server_changes,
            "isPro": is_pro,
        }
    except Exception as e:
        logger.error(f"Delta sync error for user {user_id}: {e}")
        raise HTTPException(
            status_code=400,
            detail={"error": "DeltaSyncError", "message": str(e)},
        )


# In-memory pairing code map with DB fallback for pairing resilience
_in_memory_pairs: dict[str, tuple[str, float]] = {}


@router.post("/pair/request")
@router.post("/api/comics8/pair/request")
async def pair_request(
    user_info: tuple[str, str] = Depends(get_sync_user),
    session: AsyncSession = Depends(get_db),
):
    user_id, sync_key = user_info
    settings = get_settings()
    ttl = settings.PAIR_CODE_TTL_SECONDS
    now_ts = time.time()
    now_dt = datetime.now(timezone.utc)
    exp_dt = datetime.fromtimestamp(now_ts + ttl, tz=timezone.utc)

    # Clean in-memory expired
    for c in list(_in_memory_pairs.keys()):
        if _in_memory_pairs[c][1] < now_ts or _in_memory_pairs[c][0] == sync_key:
            del _in_memory_pairs[c]

    # Generate 6-digit code
    while True:
        code = f"{random.randint(100000, 999999)}"
        if code not in _in_memory_pairs:
            break

    _in_memory_pairs[code] = (sync_key, now_ts + ttl)

    # Also persist to DB
    try:
        # Delete old codes for this key
        await session.execute(delete(PairingCode).where(PairingCode.sync_key == sync_key))
        p_code = PairingCode(code=code, sync_key=sync_key, expires_at=exp_dt)
        session.add(p_code)
        await session.commit()
    except Exception as e:
        logger.warning(f"Failed to persist pairing code to DB: {e}")

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
    payload: PairConfirmRequest,
    session: AsyncSession = Depends(get_db),
):
    code = payload.code.strip().replace(" ", "").replace("-", "")
    if not code:
        raise HTTPException(
            status_code=400,
            detail={"success": False, "message": "페어링 코드를 입력해주세요."},
        )

    now_ts = time.time()
    now_dt = datetime.now(timezone.utc)
    sync_key = None

    # Check in-memory first
    entry = _in_memory_pairs.pop(code, None)
    if entry:
        k, exp = entry
        if exp >= now_ts:
            sync_key = k

    # Always delete from DB to prevent re-use
    stmt = select(PairingCode).where(PairingCode.code == code, PairingCode.expires_at > now_dt)
    res = await session.execute(stmt)
    p_code = res.scalar_one_or_none()
    if p_code:
        if not sync_key:
            sync_key = p_code.sync_key
        await session.delete(p_code)
        await session.commit()

    if not sync_key:
        raise HTTPException(
            status_code=400,
            detail={"success": False, "message": "유효하지 않거나 만료된 페어링 코드입니다."},
        )

    return {
        "success": True,
        "syncKey": sync_key,
    }
