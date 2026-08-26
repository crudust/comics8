#!/usr/bin/env python3
"""
Comics8 SQLite to PostgreSQL Migration Script
Migrates all legacy user_*.db SQLite databases into the new PostgreSQL database.
"""
from __future__ import annotations

import argparse
import asyncio
import glob
import logging
import os
import re
import sqlite3
import sys
import time
from typing import Any
from sqlalchemy import select, text
from sqlalchemy.ext.asyncio import AsyncSession, create_async_engine

# Ensure server package path
sys.path.insert(0, os.path.dirname(__file__))

from app.config import get_settings
from app.database import Base, init_db
from app.models import (
    User,
    UserFavorite,
    UserHistory,
    UserReadEpisode,
    UserReaderSetting,
    UserTombstone,
)

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(message)s",
)
logger = logging.getLogger("comics8.migrate")


def get_sqlite_table_columns(conn: sqlite3.Connection, table: str) -> set[str]:
    try:
        return {row[1] for row in conn.execute(f"PRAGMA table_info({table})")}
    except Exception:
        return set()


def migrate_single_sqlite_db(
    db_path: str,
    user_id: str,
) -> dict[str, list[dict[str, Any]]]:
    """Reads all data from a user's SQLite database and converts to standard dictionaries."""
    conn = sqlite3.connect(db_path)
    conn.row_factory = sqlite3.Row
    cur = conn.cursor()

    result = {
        "favorites": [],
        "history": [],
        "read_episodes": [],
        "reader_settings": [],
        "tombstones": [],
    }

    # 1. Favorites
    fav_cols = get_sqlite_table_columns(conn, "favorites")
    if fav_cols:
        rows = cur.execute("SELECT * FROM favorites").fetchall()
        for r in rows:
            row = dict(r)
            source_id = (row.get("sourceId") or "eleven").strip()
            toon_id = str(row.get("toonId") or row.get("id") or "").strip()
            if not toon_id:
                continue
            result["favorites"].append({
                "source_id": source_id,
                "toon_id": toon_id,
                "title": row.get("title") or "",
                "thumb_url": row.get("thumbUrl") or "",
                "href": row.get("href") or "",
                "genre": row.get("genre") or "",
                "updated_at": row.get("updatedAt"),
                "saved_at": int(row.get("savedAt") or 0),
                "sync_updated_at": int(row.get("syncUpdatedAt") or row.get("savedAt") or 0),
            })

    # 2. History
    hist_cols = get_sqlite_table_columns(conn, "history")
    if hist_cols:
        rows = cur.execute("SELECT * FROM history").fetchall()
        for r in rows:
            row = dict(r)
            source_id = (row.get("sourceId") or "eleven").strip()
            toon_id = str(row.get("toonId") or "").strip()
            if not toon_id:
                continue
            result["history"].append({
                "source_id": source_id,
                "toon_id": toon_id,
                "toon_title": row.get("toonTitle") or "",
                "toon_thumb_url": row.get("toonThumbUrl") or "",
                "toon_href": row.get("toonHref") or "",
                "last_wr_id": str(row.get("lastWrId") or ""),
                "last_episode_title": row.get("lastEpisodeTitle") or "",
                "last_episode_href": row.get("lastEpisodeHref") or "",
                "last_read_order": int(row.get("lastReadOrder") or 1),
                "total_episodes": int(row.get("totalEpisodes") or 1),
                "last_read_at": int(row.get("lastReadAt") or 0),
                "next_wr_id": row.get("nextWrId"),
                "next_episode_title": row.get("nextEpisodeTitle"),
                "next_episode_href": row.get("nextEpisodeHref"),
                "has_new": bool(row.get("hasNew", 0)),
                "sync_updated_at": int(row.get("syncUpdatedAt") or row.get("lastReadAt") or 0),
            })

    # 3. Read Episodes
    ep_cols = get_sqlite_table_columns(conn, "read_episodes")
    if ep_cols:
        rows = cur.execute("SELECT * FROM read_episodes").fetchall()
        for r in rows:
            row = dict(r)
            source_id = (row.get("sourceId") or "eleven").strip()
            toon_id = str(row.get("toonId") or "").strip()
            wr_id = str(row.get("wrId") or "").strip()
            if not toon_id or not wr_id:
                continue
            result["read_episodes"].append({
                "source_id": source_id,
                "toon_id": toon_id,
                "wr_id": wr_id,
                "read_at": int(row.get("readAt") or 0),
                "last_page": int(row.get("lastPage") or 0),
                "sync_updated_at": int(row.get("syncUpdatedAt") or row.get("readAt") or 0),
            })

    # 4. Reader Settings
    set_cols = get_sqlite_table_columns(conn, "reader_settings")
    if set_cols:
        rows = cur.execute("SELECT * FROM reader_settings").fetchall()
        for r in rows:
            row = dict(r)
            source_id = (row.get("sourceId") or "eleven").strip()
            toon_id = str(row.get("toonId") or "").strip()
            if not toon_id:
                continue
            result["reader_settings"].append({
                "source_id": source_id,
                "toon_id": toon_id,
                "view_mode": row.get("viewMode") or "SINGLE",
                "read_direction": row.get("readDirection") or "RIGHT_TO_LEFT",
                "split_mode": row.get("splitMode") or "FIT",
                "updated_at": int(row.get("updatedAt") or 0),
                "sync_updated_at": int(row.get("syncUpdatedAt") or row.get("updatedAt") or 0),
            })

    # 5. Tombstones
    tomb_cols = get_sqlite_table_columns(conn, "tombstones")
    if tomb_cols:
        rows = cur.execute("SELECT * FROM tombstones").fetchall()
        for r in rows:
            row = dict(r)
            e_type = (row.get("entityType") or "").strip().upper()
            e_id = (row.get("entityId") or "").strip()
            if not e_id:
                continue
            if ":" not in e_id:
                e_id = f"eleven:{e_id}"
            result["tombstones"].append({
                "entity_type": e_type,
                "entity_id": e_id,
                "deleted_at": int(row.get("deletedAt") or 0),
            })

    conn.close()
    return result


async def save_user_data_to_pg(
    session: AsyncSession,
    user_id: str,
    data: dict[str, list[dict[str, Any]]],
) -> dict[str, int]:
    counts = {
        "favorites": 0,
        "history": 0,
        "read_episodes": 0,
        "reader_settings": 0,
        "tombstones": 0,
    }

    # Ensure user exists
    user = await session.get(User, user_id)
    if user is None:
        user = User(user_id=user_id)
        session.add(user)
        await session.flush()

    # 1. Favorites
    for item in data["favorites"]:
        fav = await session.get(UserFavorite, (user_id, item["source_id"], item["toon_id"]))
        if fav is None:
            fav = UserFavorite(
                user_id=user_id,
                source_id=item["source_id"],
                toon_id=item["toon_id"],
                title=item["title"],
                thumb_url=item["thumb_url"],
                href=item["href"],
                genre=item["genre"],
                updated_at=item["updated_at"],
                saved_at=item["saved_at"],
                sync_updated_at=item["sync_updated_at"],
            )
            session.add(fav)
            counts["favorites"] += 1
        elif item["saved_at"] >= fav.saved_at:
            fav.title = item["title"]
            fav.thumb_url = item["thumb_url"]
            fav.href = item["href"]
            fav.genre = item["genre"]
            fav.updated_at = item["updated_at"]
            fav.saved_at = item["saved_at"]
            fav.sync_updated_at = item["sync_updated_at"]
            counts["favorites"] += 1

    # 2. History
    for item in data["history"]:
        hist = await session.get(UserHistory, (user_id, item["source_id"], item["toon_id"]))
        if hist is None:
            hist = UserHistory(
                user_id=user_id,
                source_id=item["source_id"],
                toon_id=item["toon_id"],
                toon_title=item["toon_title"],
                toon_thumb_url=item["toon_thumb_url"],
                toon_href=item["toon_href"],
                last_wr_id=item["last_wr_id"],
                last_episode_title=item["last_episode_title"],
                last_episode_href=item["last_episode_href"],
                last_read_order=item["last_read_order"],
                total_episodes=item["total_episodes"],
                last_read_at=item["last_read_at"],
                next_wr_id=item["next_wr_id"],
                next_episode_title=item["next_episode_title"],
                next_episode_href=item["next_episode_href"],
                has_new=item["has_new"],
                sync_updated_at=item["sync_updated_at"],
            )
            session.add(hist)
            counts["history"] += 1
        elif item["last_read_at"] >= hist.last_read_at:
            hist.toon_title = item["toon_title"]
            hist.toon_thumb_url = item["toon_thumb_url"]
            hist.toon_href = item["toon_href"]
            hist.last_wr_id = item["last_wr_id"]
            hist.last_episode_title = item["last_episode_title"]
            hist.last_episode_href = item["last_episode_href"]
            hist.last_read_order = item["last_read_order"]
            hist.total_episodes = item["total_episodes"]
            hist.last_read_at = item["last_read_at"]
            hist.next_wr_id = item["next_wr_id"]
            hist.next_episode_title = item["next_episode_title"]
            hist.next_episode_href = item["next_episode_href"]
            hist.has_new = item["has_new"]
            hist.sync_updated_at = item["sync_updated_at"]
            counts["history"] += 1

    # 3. Read Episodes
    for item in data["read_episodes"]:
        ep = await session.get(UserReadEpisode, (user_id, item["source_id"], item["toon_id"], item["wr_id"]))
        if ep is None:
            ep = UserReadEpisode(
                user_id=user_id,
                source_id=item["source_id"],
                toon_id=item["toon_id"],
                wr_id=item["wr_id"],
                read_at=item["read_at"],
                last_page=item["last_page"],
                sync_updated_at=item["sync_updated_at"],
            )
            session.add(ep)
            counts["read_episodes"] += 1
        elif item["read_at"] >= ep.read_at:
            ep.read_at = item["read_at"]
            ep.last_page = item["last_page"]
            ep.sync_updated_at = item["sync_updated_at"]
            counts["read_episodes"] += 1

    # 4. Reader Settings
    for item in data["reader_settings"]:
        setting = await session.get(UserReaderSetting, (user_id, item["source_id"], item["toon_id"]))
        if setting is None:
            setting = UserReaderSetting(
                user_id=user_id,
                source_id=item["source_id"],
                toon_id=item["toon_id"],
                view_mode=item["view_mode"],
                read_direction=item["read_direction"],
                split_mode=item["split_mode"],
                updated_at=item["updated_at"],
                sync_updated_at=item["sync_updated_at"],
            )
            session.add(setting)
            counts["reader_settings"] += 1
        elif item["updated_at"] >= setting.updated_at:
            setting.view_mode = item["view_mode"]
            setting.read_direction = item["read_direction"]
            setting.split_mode = item["split_mode"]
            setting.updated_at = item["updated_at"]
            setting.sync_updated_at = item["sync_updated_at"]
            counts["reader_settings"] += 1

    # 5. Tombstones
    for item in data["tombstones"]:
        tomb = await session.get(UserTombstone, (user_id, item["entity_type"], item["entity_id"]))
        if tomb is None:
            tomb = UserTombstone(
                user_id=user_id,
                entity_type=item["entity_type"],
                entity_id=item["entity_id"],
                deleted_at=item["deleted_at"],
            )
            session.add(tomb)
            counts["tombstones"] += 1
        else:
            tomb.deleted_at = max(tomb.deleted_at, item["deleted_at"])
            counts["tombstones"] += 1

    await session.commit()
    return counts


async def run_migration(data_dir: str, db_url: str, dry_run: bool = False):
    logger.info(f"Starting SQLite to PostgreSQL migration from {data_dir}...")
    db_files = glob.glob(os.path.join(data_dir, "user_*.db"))
    if not db_files:
        logger.info(f"No user_*.db files found in {data_dir}. Searching in parent directory...")
        db_files = glob.glob(os.path.join(data_dir, "**", "user_*.db"), recursive=True)

    if not db_files:
        logger.warning(f"No SQLite database files found to migrate.")
        return

    logger.info(f"Found {len(db_files)} SQLite user database files.")

    engine = create_async_engine(db_url, echo=False)
    if not dry_run:
        await init_db(engine)

    total_stats = {
        "users": 0,
        "favorites": 0,
        "history": 0,
        "read_episodes": 0,
        "reader_settings": 0,
        "tombstones": 0,
    }

    from sqlalchemy.ext.asyncio import async_sessionmaker

    session_factory = async_sessionmaker(bind=engine, class_=AsyncSession, expire_on_commit=False)

    for db_path in db_files:
        filename = os.path.basename(db_path)
        # Extract user_id from user_<id>.db
        match = re.match(r"^user_(.+)\.db$", filename)
        if not match:
            continue
        user_id = match.group(1)
        logger.info(f"Processing user {user_id} ({filename})...")

        try:
            data = migrate_single_sqlite_db(db_path, user_id)
            if dry_run:
                logger.info(
                    f" [Dry Run] User {user_id}: {len(data['favorites'])} favs, "
                    f"{len(data['history'])} history, {len(data['read_episodes'])} episodes, "
                    f"{len(data['reader_settings'])} settings, {len(data['tombstones'])} tombstones."
                )
                total_stats["users"] += 1
                total_stats["favorites"] += len(data["favorites"])
                total_stats["history"] += len(data["history"])
                total_stats["read_episodes"] += len(data["read_episodes"])
                total_stats["reader_settings"] += len(data["reader_settings"])
                total_stats["tombstones"] += len(data["tombstones"])
            else:
                async with session_factory() as session:
                    counts = await save_user_data_to_pg(session, user_id, data)
                total_stats["users"] += 1
                for k, v in counts.items():
                    total_stats[k] += v
                logger.info(f" Migrated user {user_id}: {counts}")
        except Exception as e:
            logger.error(f"Failed to migrate {db_path}: {e}", exc_info=True)

    await engine.dispose()
    logger.info("==================================================")
    logger.info(f"Migration completed {'(DRY RUN)' if dry_run else 'SUCCESSFULLY'}!")
    logger.info(f"• Total Users:          {total_stats['users']}")
    logger.info(f"• Total Favorites:      {total_stats['favorites']}")
    logger.info(f"• Total History:        {total_stats['history']}")
    logger.info(f"• Total Read Episodes:  {total_stats['read_episodes']}")
    logger.info(f"• Total Reader Settings:{total_stats['reader_settings']}")
    logger.info(f"• Total Tombstones:     {total_stats['tombstones']}")
    logger.info("==================================================")


def main():
    settings = get_settings()
    parser = argparse.ArgumentParser(description="Comics8 SQLite to PostgreSQL Migration Tool")
    parser.add_argument(
        "--data-dir",
        default=settings.DATA_DIR,
        help=f"Directory containing user_*.db files (default: {settings.DATA_DIR})",
    )
    parser.add_argument(
        "--db-url",
        default=settings.DATABASE_URL,
        help=f"Target PostgreSQL async URL (default: {settings.DATABASE_URL})",
    )
    parser.add_argument(
        "--dry-run",
        action="store_true",
        help="Inspect SQLite files and print counts without writing to PostgreSQL",
    )

    args = parser.parse_args()
    asyncio.run(run_migration(args.data_dir, args.db_url, args.dry_run))


if __name__ == "__main__":
    main()
