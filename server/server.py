from __future__ import annotations

import os
import json
import time
import sqlite3
import hashlib
import re
import urllib.request
import urllib.error
import random
import threading
from http.server import ThreadingHTTPServer, BaseHTTPRequestHandler
from urllib.parse import parse_qs, urljoin, urlparse

DATA_DIR = os.environ.get("DATA_DIR", os.path.expanduser("~/comics8-sync/data"))
DOWNLOADS_DIR = os.path.join(DATA_DIR, "downloads")
VERSION_FILE = os.path.join(DATA_DIR, "version.json")
os.makedirs(DOWNLOADS_DIR, exist_ok=True)
PORT = int(os.environ.get("PORT", "8905"))

DEFAULT_SOURCE_ID = "eleven"
SYNC_SCHEMA_CURRENT = 2
SYNC_SCHEMA_HEADER = "X-Comics8-Sync-Schema"
DEFAULT_REFERER = "http://103.204.13.68:8904"
CORS_ALLOW_HEADERS = (
    "Authorization, Content-Type, X-Sync-Key, X-Comics8-Sync-Schema, Range, Referer"
)
# Keep in sync with ComicSource.ownsHost() in core (ElevenToonSource, HitomiSource).
# New sites that need the sync proxy must add their hosts here and in ownsHost.
_EXACT_PROXY_HOSTS = {
    "hitomi.la",
    "tn.hitomi.la",
    "11toon.com",
    "11toon7.com",
    "11toon8.com",
    "www.11toon.com",
    "www.11toon7.com",
    "www.11toon8.com",
    "103.204.13.68",
}
_PL_HOST_RE = re.compile(r"(www\.)?pl\d{4}\.com\Z")
_TOON_HOST_RE = re.compile(r"(www\.)?11toon\d*\.com\Z")
PROXY_REDIRECT_STATUSES = {301, 302, 303, 307, 308}
MAX_PROXY_REDIRECTS = 5
DEFAULT_PROXY_UA = (
    "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) "
    "Chrome/126.0.0.0 Mobile Safari/537.36"
)

_PAIR_CODES: dict[str, tuple[str, float]] = {}
_PAIR_LOCK = threading.Lock()


def _clean_expired_pair_codes():
    now = time.time()
    expired = [c for c, (_, exp) in _PAIR_CODES.items() if exp < now]
    for c in expired:
        del _PAIR_CODES[c]


def request_pair_code(sync_key: str, ttl_seconds: int = 300) -> str:
    with _PAIR_LOCK:
        _clean_expired_pair_codes()
        for c in list(_PAIR_CODES.keys()):
            if _PAIR_CODES[c][0] == sync_key:
                del _PAIR_CODES[c]
        while True:
            code = f"{random.randint(100000, 999999)}"
            if code not in _PAIR_CODES:
                break
        _PAIR_CODES[code] = (sync_key, time.time() + ttl_seconds)
        return code


def confirm_pair_code(code: str) -> str | None:
    with _PAIR_LOCK:
        _clean_expired_pair_codes()
        entry = _PAIR_CODES.pop(code, None)
        if entry is None:
            return None
        sync_key, exp = entry
        if time.time() > exp:
            return None
        return sync_key


def get_safe_user_id(key: str) -> str:
    cleaned = re.sub(r'[^a-zA-Z0-9_\-]', '', key.strip())
    if 3 <= len(cleaned) <= 64:
        return cleaned
    return hashlib.sha256(key.strip().encode('utf-8')).hexdigest()[:24]

def get_user_db_path(user_id: str) -> str:
    os.makedirs(DATA_DIR, exist_ok=True)
    return os.path.join(DATA_DIR, f"user_{user_id}.db")


def parse_sync_schema(value: str | None) -> int:
    if value is None or str(value).strip() == "":
        return 1
    try:
        return int(str(value).strip())
    except ValueError:
        return 1


def parse_entity_id(entity_id: str | None) -> tuple[str, str]:
    raw = (entity_id or "").strip()
    if ":" in raw:
        source, local = raw.split(":", 1)
        if source and local:
            return source, local
    return DEFAULT_SOURCE_ID, raw


def row_source_id(row: dict | None) -> str:
    if not row:
        return DEFAULT_SOURCE_ID
    return (row.get("sourceId") or DEFAULT_SOURCE_ID).strip() or DEFAULT_SOURCE_ID


def accept_inbound_row(row: dict) -> bool:
    source = row_source_id(row)
    return source != "local"


def favorite_local_id(row: dict) -> str | None:
    value = row.get("id") or row.get("toonId")
    if value is None:
        return None
    text = str(value).strip()
    return text or None


def emit_favorite(row: dict) -> dict | None:
    source_id = row_source_id(row)
    toon_id = favorite_local_id(row)
    if not toon_id:
        return None
    return {
        "id": toon_id,
        "sourceId": source_id,
        "title": row.get("title", ""),
        "thumbUrl": row.get("thumbUrl", ""),
        "href": row.get("href", ""),
        "genre": row.get("genre", ""),
        "updatedAt": row.get("updatedAt"),
        "savedAt": row.get("savedAt"),
    }


def emit_work_row(row: dict) -> dict | None:
    source_id = row_source_id(row)
    out = dict(row)
    out["sourceId"] = source_id
    if "hasNew" in out:
        out["hasNew"] = bool(out.get("hasNew"))
    return out


def emit_tombstone(row: dict) -> dict | None:
    entity_id = row.get("entityId")
    if not entity_id:
        return None
    return {
        "entityType": row.get("entityType"),
        "entityId": entity_id,
        "deletedAt": row.get("deletedAt"),
    }


def is_allowed_proxy_host(host: str | None) -> bool:
    if not host:
        return False
    normalized = host.lower().strip().strip(".")
    if not normalized:
        return False
    if normalized in _EXACT_PROXY_HOSTS:
        return True
    if normalized.endswith(".gold-usergeneratedcontent.net"):
        return True
    if normalized == "hitomi.la" or normalized.endswith(".hitomi.la"):
        return True
    if _TOON_HOST_RE.fullmatch(normalized):
        return True
    if _PL_HOST_RE.fullmatch(normalized):
        return True
    return False


def allowed_proxy_url(url: str | None) -> bool:
    if not url:
        return False
    parsed = urlparse(url)
    if parsed.scheme not in ("http", "https"):
        return False
    return is_allowed_proxy_host(parsed.hostname)


def next_proxy_url(current_url: str, location: str | None) -> str | None:
    if not location or not str(location).strip():
        return None
    nxt = urljoin(current_url, str(location).strip())
    if not allowed_proxy_url(nxt):
        return None
    return nxt


def _incoming_header(incoming, name: str, default: str = "") -> str:
    if incoming is None:
        return default
    getter = getattr(incoming, "get", None)
    if getter:
        value = getter(name)
        if value:
            return value
    if isinstance(incoming, dict):
        wanted = name.lower()
        for key, value in incoming.items():
            if str(key).lower() == wanted and value:
                return value
    return default


def build_proxy_forward_headers(query: dict | None, incoming_headers, include_accept: bool = True) -> dict:
    headers = {
        "User-Agent": _incoming_header(incoming_headers, "User-Agent", DEFAULT_PROXY_UA),
        "Accept-Encoding": "identity",
    }
    if include_accept:
        headers["Accept"] = _incoming_header(
            incoming_headers, "Accept", "text/html,application/xhtml+xml;q=0.9,*/*;q=0.8"
        )
        headers["Accept-Language"] = _incoming_header(
            incoming_headers, "Accept-Language", "ko-KR,ko;q=0.9,en;q=0.8"
        )
    referer_q = ""
    if query:
        referer_q = (query.get("referer") or [""])[0]
    referer = (referer_q or _incoming_header(incoming_headers, "Referer") or DEFAULT_REFERER).strip()
    if referer:
        headers["Referer"] = referer
    range_header = _incoming_header(incoming_headers, "Range")
    if range_header:
        headers["Range"] = range_header
    return headers


def passthrough_proxy_headers(resp_headers, decoded: bool = False) -> list[tuple[str, str]]:
    content_type = "text/html"
    if resp_headers is not None and resp_headers.get("Content-Type"):
        content_type = resp_headers.get("Content-Type")
    headers = [("Content-Type", content_type)]
    # Never attach compressed Content-Length/Content-Range to a decoded body.
    if decoded:
        return headers
    if resp_headers is None:
        return headers
    for name in ("Content-Range", "Content-Length", "Content-Encoding"):
        value = resp_headers.get(name)
        if value:
            headers.append((name, value))
    return headers


class ProxiedResponse:
    def __init__(self, status_code: int, headers: dict[str, str], fp):
        self.status_code = status_code
        self.headers = headers
        self.raw = fp

    def close(self):
        if self.raw:
            try:
                self.raw.close()
            except Exception:
                pass


class _NoRedirectHandler(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def fetch_proxied(method: str, url: str, headers: dict, timeout: int, stream: bool = False):
    current = url
    opener = urllib.request.build_opener(_NoRedirectHandler)
    for _ in range(MAX_PROXY_REDIRECTS + 1):
        if not allowed_proxy_url(current):
            return "forbidden", None
        req = urllib.request.Request(current, headers=headers, method=method)
        try:
            resp = opener.open(req, timeout=timeout)
            resp_headers = {k: v for k, v in resp.headers.items()}
            return "ok", ProxiedResponse(resp.status, resp_headers, resp)
        except urllib.error.HTTPError as e:
            if e.code in PROXY_REDIRECT_STATUSES:
                location = e.headers.get("Location")
                nxt = next_proxy_url(current, location)
                if nxt is None:
                    if not location:
                        resp_headers = {k: v for k, v in e.headers.items()}
                        return "ok", ProxiedResponse(e.code, resp_headers, e)
                    e.close()
                    return "forbidden", None
                if e.code == 303:
                    method = "GET"
                e.close()
                current = nxt
                continue
            resp_headers = {k: v for k, v in e.headers.items()} if e.headers else {}
            return "ok", ProxiedResponse(e.code, resp_headers, e)
        except (TimeoutError, urllib.error.URLError) as e:
            if isinstance(e, TimeoutError) or isinstance(getattr(e, "reason", None), TimeoutError):
                raise TimeoutError("Target server timed out")
            raise
    return "redirects", None


def _table_columns(conn: sqlite3.Connection, table: str) -> set[str]:
    return {row[1] for row in conn.execute(f"PRAGMA table_info({table})")}


def _ensure_user_indexes(conn: sqlite3.Connection):
    conn.execute("CREATE INDEX IF NOT EXISTS idx_fav_sync ON favorites(syncUpdatedAt);")
    conn.execute("CREATE INDEX IF NOT EXISTS idx_hist_sync ON history(syncUpdatedAt);")
    conn.execute("CREATE INDEX IF NOT EXISTS idx_ep_sync ON read_episodes(syncUpdatedAt);")
    conn.execute("CREATE INDEX IF NOT EXISTS idx_set_sync ON reader_settings(syncUpdatedAt);")
    conn.execute("CREATE INDEX IF NOT EXISTS idx_tomb_deleted ON tombstones(deletedAt);")
    if "splitMode" not in _table_columns(conn, "reader_settings"):
        conn.execute("ALTER TABLE reader_settings ADD COLUMN splitMode TEXT DEFAULT 'FIT';")


def _rebuild_user_tables_v2(conn: sqlite3.Connection):
    conn.execute("DROP TABLE IF EXISTS favorites_new")
    conn.execute("""
    CREATE TABLE favorites_new (
        sourceId TEXT NOT NULL,
        toonId TEXT NOT NULL,
        title TEXT,
        thumbUrl TEXT,
        href TEXT,
        genre TEXT,
        updatedAt TEXT,
        savedAt INTEGER,
        syncUpdatedAt INTEGER,
        PRIMARY KEY (sourceId, toonId)
    );
    """)
    conn.execute("""
    INSERT INTO favorites_new (sourceId, toonId, title, thumbUrl, href, genre, updatedAt, savedAt, syncUpdatedAt)
    SELECT 'eleven', id, title, thumbUrl, href, genre, updatedAt, savedAt, syncUpdatedAt FROM favorites
    """)
    conn.execute("DROP TABLE favorites")
    conn.execute("ALTER TABLE favorites_new RENAME TO favorites")

    conn.execute("DROP TABLE IF EXISTS history_new")
    conn.execute("""
    CREATE TABLE history_new (
        sourceId TEXT NOT NULL,
        toonId TEXT NOT NULL,
        toonTitle TEXT,
        toonThumbUrl TEXT,
        toonHref TEXT,
        lastWrId TEXT,
        lastEpisodeTitle TEXT,
        lastEpisodeHref TEXT,
        lastReadOrder INTEGER,
        totalEpisodes INTEGER,
        lastReadAt INTEGER,
        nextWrId TEXT,
        nextEpisodeTitle TEXT,
        nextEpisodeHref TEXT,
        hasNew INTEGER DEFAULT 0,
        syncUpdatedAt INTEGER,
        PRIMARY KEY (sourceId, toonId)
    );
    """)
    conn.execute("""
    INSERT INTO history_new (
        sourceId, toonId, toonTitle, toonThumbUrl, toonHref, lastWrId, lastEpisodeTitle, lastEpisodeHref,
        lastReadOrder, totalEpisodes, lastReadAt, nextWrId, nextEpisodeTitle, nextEpisodeHref, hasNew, syncUpdatedAt
    )
    SELECT 'eleven', toonId, toonTitle, toonThumbUrl, toonHref, lastWrId, lastEpisodeTitle, lastEpisodeHref,
           lastReadOrder, totalEpisodes, lastReadAt, nextWrId, nextEpisodeTitle, nextEpisodeHref, hasNew, syncUpdatedAt
    FROM history
    """)
    conn.execute("DROP TABLE history")
    conn.execute("ALTER TABLE history_new RENAME TO history")

    conn.execute("DROP TABLE IF EXISTS read_episodes_new")
    conn.execute("""
    CREATE TABLE read_episodes_new (
        sourceId TEXT NOT NULL,
        toonId TEXT NOT NULL,
        wrId TEXT NOT NULL,
        readAt INTEGER,
        lastPage INTEGER DEFAULT 0,
        syncUpdatedAt INTEGER,
        PRIMARY KEY (sourceId, toonId, wrId)
    );
    """)
    conn.execute("""
    INSERT INTO read_episodes_new (sourceId, toonId, wrId, readAt, lastPage, syncUpdatedAt)
    SELECT 'eleven', toonId, wrId, readAt, lastPage, syncUpdatedAt FROM read_episodes
    """)
    conn.execute("DROP TABLE read_episodes")
    conn.execute("ALTER TABLE read_episodes_new RENAME TO read_episodes")

    conn.execute("DROP TABLE IF EXISTS reader_settings_new")
    conn.execute("""
    CREATE TABLE reader_settings_new (
        sourceId TEXT NOT NULL,
        toonId TEXT NOT NULL,
        viewMode TEXT,
        readDirection TEXT,
        updatedAt INTEGER,
        syncUpdatedAt INTEGER,
        PRIMARY KEY (sourceId, toonId)
    );
    """)
    conn.execute("""
    INSERT INTO reader_settings_new (sourceId, toonId, viewMode, readDirection, updatedAt, syncUpdatedAt)
    SELECT 'eleven', toonId, viewMode, readDirection, updatedAt, syncUpdatedAt FROM reader_settings
    """)
    conn.execute("DROP TABLE reader_settings")
    conn.execute("ALTER TABLE reader_settings_new RENAME TO reader_settings")


def _prefix_tombstone_entity_ids(conn: sqlite3.Connection):
    rows = conn.execute("SELECT entityType, entityId, deletedAt FROM tombstones").fetchall()
    for entity_type, entity_id, deleted_at in rows:
        if not entity_id or ":" in entity_id:
            continue
        prefixed = f"{DEFAULT_SOURCE_ID}:{entity_id}"
        existing = conn.execute(
            "SELECT deletedAt FROM tombstones WHERE entityType = ? AND entityId = ?",
            (entity_type, prefixed),
        ).fetchone()
        if existing:
            conn.execute(
                "UPDATE tombstones SET deletedAt = MAX(deletedAt, ?) WHERE entityType = ? AND entityId = ?",
                (deleted_at, entity_type, prefixed),
            )
            conn.execute(
                "DELETE FROM tombstones WHERE entityType = ? AND entityId = ?",
                (entity_type, entity_id),
            )
        else:
            conn.execute(
                "UPDATE tombstones SET entityId = ? WHERE entityType = ? AND entityId = ?",
                (prefixed, entity_type, entity_id),
            )


def migrate_user_db(conn: sqlite3.Connection):
    user_version = conn.execute("PRAGMA user_version").fetchone()[0]
    fav_cols = _table_columns(conn, "favorites")
    if not fav_cols:
        return
    if user_version != 0 and "sourceId" in fav_cols:
        return
    with conn:
        if "sourceId" not in fav_cols:
            _rebuild_user_tables_v2(conn)
        _prefix_tombstone_entity_ids(conn)
        _ensure_user_indexes(conn)
        conn.execute("PRAGMA user_version = 2")


def init_user_db(conn: sqlite3.Connection, user_id: str):
    with conn:
        conn.execute("""
        CREATE TABLE IF NOT EXISTS favorites (
            sourceId TEXT NOT NULL,
            toonId TEXT NOT NULL,
            title TEXT,
            thumbUrl TEXT,
            href TEXT,
            genre TEXT,
            updatedAt TEXT,
            savedAt INTEGER,
            syncUpdatedAt INTEGER,
            PRIMARY KEY (sourceId, toonId)
        );
        """)

        conn.execute("""
        CREATE TABLE IF NOT EXISTS history (
            sourceId TEXT NOT NULL,
            toonId TEXT NOT NULL,
            toonTitle TEXT,
            toonThumbUrl TEXT,
            toonHref TEXT,
            lastWrId TEXT,
            lastEpisodeTitle TEXT,
            lastEpisodeHref TEXT,
            lastReadOrder INTEGER,
            totalEpisodes INTEGER,
            lastReadAt INTEGER,
            nextWrId TEXT,
            nextEpisodeTitle TEXT,
            nextEpisodeHref TEXT,
            hasNew INTEGER DEFAULT 0,
            syncUpdatedAt INTEGER,
            PRIMARY KEY (sourceId, toonId)
        );
        """)

        conn.execute("""
        CREATE TABLE IF NOT EXISTS read_episodes (
            sourceId TEXT NOT NULL,
            toonId TEXT NOT NULL,
            wrId TEXT NOT NULL,
            readAt INTEGER,
            lastPage INTEGER DEFAULT 0,
            syncUpdatedAt INTEGER,
            PRIMARY KEY (sourceId, toonId, wrId)
        );
        """)

        conn.execute("""
        CREATE TABLE IF NOT EXISTS reader_settings (
            sourceId TEXT NOT NULL,
            toonId TEXT NOT NULL,
            viewMode TEXT,
            readDirection TEXT,
            splitMode TEXT DEFAULT 'FIT',
            updatedAt INTEGER,
            syncUpdatedAt INTEGER,
            PRIMARY KEY (sourceId, toonId)
        );
        """)

        conn.execute("""
        CREATE TABLE IF NOT EXISTS tombstones (
            entityType TEXT,
            entityId TEXT,
            deletedAt INTEGER,
            PRIMARY KEY (entityType, entityId)
        );
        """)
        _ensure_user_indexes(conn)

    migrate_user_db(conn)

    # Migrate from existing JSON if DB is empty
    legacy_json = os.path.join(DATA_DIR, f"user_{user_id}.json")
    if os.path.exists(legacy_json):
        cursor = conn.cursor()
        cursor.execute("SELECT COUNT(*) FROM favorites")
        fav_count = cursor.fetchone()[0]
        cursor.execute("SELECT COUNT(*) FROM history")
        hist_count = cursor.fetchone()[0]
        if fav_count == 0 and hist_count == 0:
            try:
                with open(legacy_json, "r", encoding="utf-8") as f:
                    data = json.load(f)
                import_full_snapshot(conn, data, now_ms=int(time.time() * 1000), schema=1)
            except Exception as e:
                print(f"Error migrating legacy JSON for {user_id}: {e}")


def import_full_snapshot(conn: sqlite3.Connection, data: dict, now_ms: int):
    with conn:
        favs = data.get("favorites", [])
        for f in favs:
            if not accept_inbound_row(f):
                continue
            source_id = row_source_id(f)
            f_id = favorite_local_id(f)
            if not f_id:
                continue
            saved_at = f.get("savedAt") or now_ms
            conn.execute("""
            INSERT INTO favorites (sourceId, toonId, title, thumbUrl, href, genre, updatedAt, savedAt, syncUpdatedAt)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(sourceId, toonId) DO UPDATE SET
                title = excluded.title,
                thumbUrl = excluded.thumbUrl,
                href = excluded.href,
                genre = excluded.genre,
                updatedAt = excluded.updatedAt,
                savedAt = excluded.savedAt,
                syncUpdatedAt = excluded.syncUpdatedAt
            """, (source_id, f_id, f.get("title", ""), f.get("thumbUrl", ""), f.get("href", ""),
                  f.get("genre", ""), f.get("updatedAt"), saved_at, now_ms))

        hist = data.get("history", [])
        for h in hist:
            if not accept_inbound_row(h):
                continue
            source_id = row_source_id(h)
            toon_id = h.get("toonId")
            if not toon_id:
                continue
            last_read_at = h.get("lastReadAt") or now_ms
            conn.execute("""
            INSERT INTO history (sourceId, toonId, toonTitle, toonThumbUrl, toonHref, lastWrId, lastEpisodeTitle, lastEpisodeHref,
                                 lastReadOrder, totalEpisodes, lastReadAt, nextWrId, nextEpisodeTitle, nextEpisodeHref, hasNew, syncUpdatedAt)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(sourceId, toonId) DO UPDATE SET
                toonTitle = excluded.toonTitle,
                toonThumbUrl = excluded.toonThumbUrl,
                toonHref = excluded.toonHref,
                lastWrId = excluded.lastWrId,
                lastEpisodeTitle = excluded.lastEpisodeTitle,
                lastEpisodeHref = excluded.lastEpisodeHref,
                lastReadOrder = excluded.lastReadOrder,
                totalEpisodes = excluded.totalEpisodes,
                lastReadAt = excluded.lastReadAt,
                nextWrId = excluded.nextWrId,
                nextEpisodeTitle = excluded.nextEpisodeTitle,
                nextEpisodeHref = excluded.nextEpisodeHref,
                hasNew = excluded.hasNew,
                syncUpdatedAt = excluded.syncUpdatedAt
            """, (source_id, toon_id, h.get("toonTitle", ""), h.get("toonThumbUrl", ""), h.get("toonHref", ""),
                  h.get("lastWrId", ""), h.get("lastEpisodeTitle", ""), h.get("lastEpisodeHref", ""),
                  h.get("lastReadOrder", 1), h.get("totalEpisodes", 1), last_read_at,
                  h.get("nextWrId"), h.get("nextEpisodeTitle"), h.get("nextEpisodeHref"),
                  1 if h.get("hasNew") else 0, now_ms))

        eps = data.get("readEpisodes", [])
        for ep in eps:
            if not accept_inbound_row(ep):
                continue
            source_id = row_source_id(ep)
            toon_id = ep.get("toonId")
            wr_id = ep.get("wrId")
            if not toon_id or not wr_id:
                continue
            read_at = ep.get("readAt") or now_ms
            conn.execute("""
            INSERT INTO read_episodes (sourceId, toonId, wrId, readAt, lastPage, syncUpdatedAt)
            VALUES (?, ?, ?, ?, ?, ?)
            ON CONFLICT(sourceId, toonId, wrId) DO UPDATE SET
                readAt = excluded.readAt,
                lastPage = excluded.lastPage,
                syncUpdatedAt = excluded.syncUpdatedAt
            """, (source_id, toon_id, wr_id, read_at, ep.get("lastPage", 0), now_ms))

        settings = data.get("readerSettings", [])
        for s in settings:
            if not accept_inbound_row(s):
                continue
            source_id = row_source_id(s)
            toon_id = s.get("toonId")
            if not toon_id:
                continue
            up_at = s.get("updatedAt") or now_ms
            conn.execute("""
            INSERT INTO reader_settings (sourceId, toonId, viewMode, readDirection, splitMode, updatedAt, syncUpdatedAt)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(sourceId, toonId) DO UPDATE SET
                viewMode = excluded.viewMode,
                readDirection = excluded.readDirection,
                splitMode = excluded.splitMode,
                updatedAt = excluded.updatedAt,
                syncUpdatedAt = excluded.syncUpdatedAt
            """, (source_id, toon_id, s.get("viewMode", "SINGLE"), s.get("readDirection", "RIGHT_TO_LEFT"), s.get("splitMode", "FIT"), up_at, now_ms))


def export_full_snapshot(conn: sqlite3.Connection) -> dict:
    conn.row_factory = sqlite3.Row
    cursor = conn.cursor()

    favs = []
    for row in cursor.execute(
        "SELECT sourceId, toonId, title, thumbUrl, href, genre, updatedAt, savedAt FROM favorites ORDER BY savedAt DESC"
    ):
        emitted = emit_favorite(dict(row))
        if emitted:
            favs.append(emitted)

    hist = []
    for row in cursor.execute(
        "SELECT sourceId, toonId, toonTitle, toonThumbUrl, toonHref, lastWrId, lastEpisodeTitle, lastEpisodeHref, "
        "lastReadOrder, totalEpisodes, lastReadAt, nextWrId, nextEpisodeTitle, nextEpisodeHref, hasNew FROM history "
        "ORDER BY lastReadAt DESC"
    ):
        emitted = emit_work_row(dict(row))
        if emitted:
            hist.append(emitted)

    eps = []
    for row in cursor.execute("SELECT sourceId, toonId, wrId, readAt, lastPage FROM read_episodes"):
        emitted = emit_work_row(dict(row))
        if emitted:
            eps.append(emitted)

    settings = []
    for row in cursor.execute("SELECT sourceId, toonId, viewMode, readDirection, splitMode, updatedAt FROM reader_settings"):
        emitted = emit_work_row(dict(row))
        if emitted:
            settings.append(emitted)

    return {
        "version": 2,
        "appName": "Comics8",
        "exportedAt": int(time.time() * 1000),
        "favorites": favs,
        "history": hist,
        "readEpisodes": eps,
        "readerSettings": settings,
    }


def apply_delta_changes(conn: sqlite3.Connection, changes: dict, now_ms: int):
    with conn:
        tombstones = changes.get("tombstones", [])
        for t in tombstones:
            e_type = (t.get("entityType") or "").upper()
            source_id, local_id = parse_entity_id(t.get("entityId", ""))
            if not local_id or source_id == "local":
                continue
            del_at = int(t.get("deletedAt", now_ms))
            stored_id = f"{source_id}:{local_id}"
            conn.execute("""
            INSERT INTO tombstones (entityType, entityId, deletedAt)
            VALUES (?, ?, ?)
            ON CONFLICT(entityType, entityId) DO UPDATE SET deletedAt = MAX(deletedAt, excluded.deletedAt)
            """, (e_type, stored_id, del_at))
            if e_type == "FAVORITE":
                conn.execute("DELETE FROM favorites WHERE sourceId = ? AND toonId = ?", (source_id, local_id))
            elif e_type == "HISTORY":
                conn.execute("DELETE FROM history WHERE sourceId = ? AND toonId = ?", (source_id, local_id))
            elif e_type == "EPISODE":
                conn.execute("DELETE FROM read_episodes WHERE sourceId = ? AND toonId = ?", (source_id, local_id))

        for f in changes.get("favorites", []):
            if not accept_inbound_row(f):
                continue
            source_id = row_source_id(f)
            f_id = favorite_local_id(f)
            if not f_id:
                continue
            saved_at = int(f.get("savedAt", now_ms))
            conn.execute("""
            INSERT INTO favorites (sourceId, toonId, title, thumbUrl, href, genre, updatedAt, savedAt, syncUpdatedAt)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(sourceId, toonId) DO UPDATE SET
                title = excluded.title,
                thumbUrl = excluded.thumbUrl,
                href = excluded.href,
                genre = excluded.genre,
                updatedAt = excluded.updatedAt,
                savedAt = excluded.savedAt,
                syncUpdatedAt = ?
            WHERE excluded.savedAt >= favorites.savedAt
            """, (source_id, f_id, f.get("title", ""), f.get("thumbUrl", ""), f.get("href", ""),
                  f.get("genre", ""), f.get("updatedAt"), saved_at, now_ms, now_ms))
            conn.execute(
                "DELETE FROM tombstones WHERE entityType = 'FAVORITE' AND entityId = ? AND deletedAt < ?",
                (f"{source_id}:{f_id}", saved_at),
            )

        for h in changes.get("history", []):
            if not accept_inbound_row(h):
                continue
            source_id = row_source_id(h)
            toon_id = h.get("toonId")
            if not toon_id:
                continue
            last_read_at = int(h.get("lastReadAt", now_ms))
            conn.execute("""
            INSERT INTO history (sourceId, toonId, toonTitle, toonThumbUrl, toonHref, lastWrId, lastEpisodeTitle, lastEpisodeHref,
                                 lastReadOrder, totalEpisodes, lastReadAt, nextWrId, nextEpisodeTitle, nextEpisodeHref, hasNew, syncUpdatedAt)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(sourceId, toonId) DO UPDATE SET
                toonTitle = excluded.toonTitle,
                toonThumbUrl = excluded.toonThumbUrl,
                toonHref = excluded.toonHref,
                lastWrId = excluded.lastWrId,
                lastEpisodeTitle = excluded.lastEpisodeTitle,
                lastEpisodeHref = excluded.lastEpisodeHref,
                lastReadOrder = excluded.lastReadOrder,
                totalEpisodes = excluded.totalEpisodes,
                lastReadAt = excluded.lastReadAt,
                nextWrId = excluded.nextWrId,
                nextEpisodeTitle = excluded.nextEpisodeTitle,
                nextEpisodeHref = excluded.nextEpisodeHref,
                hasNew = excluded.hasNew,
                syncUpdatedAt = ?
            WHERE excluded.lastReadAt >= history.lastReadAt
            """, (source_id, toon_id, h.get("toonTitle", ""), h.get("toonThumbUrl", ""), h.get("toonHref", ""),
                  h.get("lastWrId", ""), h.get("lastEpisodeTitle", ""), h.get("lastEpisodeHref", ""),
                  h.get("lastReadOrder", 1), h.get("totalEpisodes", 1), last_read_at,
                  h.get("nextWrId"), h.get("nextEpisodeTitle"), h.get("nextEpisodeHref"),
                  1 if h.get("hasNew") else 0, now_ms, now_ms))
            conn.execute(
                "DELETE FROM tombstones WHERE entityType = 'HISTORY' AND entityId = ? AND deletedAt < ?",
                (f"{source_id}:{toon_id}", last_read_at),
            )

        for ep in changes.get("readEpisodes", []):
            if not accept_inbound_row(ep):
                continue
            source_id = row_source_id(ep)
            toon_id = ep.get("toonId")
            wr_id = ep.get("wrId")
            if not toon_id or not wr_id:
                continue
            read_at = int(ep.get("readAt", now_ms))
            conn.execute("""
            INSERT INTO read_episodes (sourceId, toonId, wrId, readAt, lastPage, syncUpdatedAt)
            VALUES (?, ?, ?, ?, ?, ?)
            ON CONFLICT(sourceId, toonId, wrId) DO UPDATE SET
                readAt = excluded.readAt,
                lastPage = excluded.lastPage,
                syncUpdatedAt = ?
            WHERE excluded.readAt >= read_episodes.readAt
            """, (source_id, toon_id, wr_id, read_at, ep.get("lastPage", 0), now_ms, now_ms))

        for s in changes.get("readerSettings", []):
            if not accept_inbound_row(s):
                continue
            source_id = row_source_id(s)
            toon_id = s.get("toonId")
            if not toon_id:
                continue
            up_at = int(s.get("updatedAt", now_ms))
            conn.execute("""
            INSERT INTO reader_settings (sourceId, toonId, viewMode, readDirection, splitMode, updatedAt, syncUpdatedAt)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(sourceId, toonId) DO UPDATE SET
                viewMode = excluded.viewMode,
                readDirection = excluded.readDirection,
                splitMode = excluded.splitMode,
                updatedAt = excluded.updatedAt,
                syncUpdatedAt = ?
            WHERE excluded.updatedAt >= reader_settings.updatedAt
            """, (source_id, toon_id, s.get("viewMode", "SINGLE"), s.get("readDirection", "RIGHT_TO_LEFT"), s.get("splitMode", "FIT"), up_at, now_ms, now_ms))


def collect_delta_changes(conn: sqlite3.Connection, since: int) -> dict:
    conn.row_factory = sqlite3.Row
    cur = conn.cursor()

    server_favs = []
    for r in cur.execute(
        "SELECT sourceId, toonId, title, thumbUrl, href, genre, updatedAt, savedAt FROM favorites WHERE syncUpdatedAt > ?",
        (since,),
    ):
        emitted = emit_favorite(dict(r))
        if emitted:
            server_favs.append(emitted)

    server_hist = []
    for r in cur.execute(
        "SELECT sourceId, toonId, toonTitle, toonThumbUrl, toonHref, lastWrId, lastEpisodeTitle, lastEpisodeHref, "
        "lastReadOrder, totalEpisodes, lastReadAt, nextWrId, nextEpisodeTitle, nextEpisodeHref, hasNew "
        "FROM history WHERE syncUpdatedAt > ?",
        (since,),
    ):
        emitted = emit_work_row(dict(r))
        if emitted:
            server_hist.append(emitted)

    server_eps = []
    for r in cur.execute(
        "SELECT sourceId, toonId, wrId, readAt, lastPage FROM read_episodes WHERE syncUpdatedAt > ?",
        (since,),
    ):
        emitted = emit_work_row(dict(r))
        if emitted:
            server_eps.append(emitted)

    server_settings = []
    for r in cur.execute(
        "SELECT sourceId, toonId, viewMode, readDirection, splitMode, updatedAt FROM reader_settings WHERE syncUpdatedAt > ?",
        (since,),
    ):
        emitted = emit_work_row(dict(r))
        if emitted:
            server_settings.append(emitted)

    server_tombs = []
    for r in cur.execute(
        "SELECT entityType, entityId, deletedAt FROM tombstones WHERE deletedAt > ?",
        (since,),
    ):
        emitted = emit_tombstone(dict(r))
        if emitted:
            server_tombs.append(emitted)

    return {
        "favorites": server_favs,
        "history": server_hist,
        "readEpisodes": server_eps,
        "readerSettings": server_settings,
        "tombstones": server_tombs,
    }

class SyncHandler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def _send_json(self, status, payload):
        try:
            body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
            self.send_response(status)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Content-Length", str(len(body)))
            self.send_header("Connection", "close")
            self.send_header("Access-Control-Allow-Origin", "*")
            self.send_header("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
            self.send_header("Access-Control-Allow-Headers", CORS_ALLOW_HEADERS)
            self.send_header(SYNC_SCHEMA_HEADER, str(SYNC_SCHEMA_CURRENT))
            self.end_headers()
            self.wfile.write(body)
            self.close_connection = True
        except Exception as e:
            print(f"Error sending response: {e}")

    def _get_sync_key(self) -> str | None:
        auth_header = self.headers.get("Authorization", "")
        if auth_header.startswith("Bearer "):
            token = auth_header[7:].strip()
            if token:
                return token
        key_header = self.headers.get("X-Sync-Key", "").strip()
        if key_header:
            return key_header
        if "?" in self.path:
            query = parse_qs(urlparse(self.path).query)
            token = query.get("key", query.get("token", [""]))[0].strip()
            if token:
                return token
        return None

    def _proxy_forward_headers(self, query: dict, include_accept: bool = True) -> dict:
        return build_proxy_forward_headers(query, self.headers, include_accept=include_accept)

    def _send_proxied_headers(self, resp):
        for name, value in passthrough_proxy_headers(resp.headers, decoded=False):
            self.send_header(name, value)
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Connection", "close")

    def _write_proxied_body(self, resp):
        raw = getattr(resp, "raw", None)
        if raw is None:
            body = resp.content if resp.content else b""
            if body:
                self.wfile.write(body)
            return
        raw.decode_content = False
        while True:
            chunk = raw.read(65536)
            if not chunk:
                break
            self.wfile.write(chunk)

    def do_HEAD(self):
        raw_path = self.path.split("?")[0].rstrip("/")
        parsed_path = raw_path if raw_path else "/"
        if parsed_path in ("/health", "/api/comics8/health", "/version", "/api/comics8/version"):
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Access-Control-Allow-Origin", "*")
            self.send_header("Connection", "close")
            self.end_headers()
            self.close_connection = True
            return

        if "/download/" in parsed_path:
            filename = parsed_path.split("/download/")[-1].strip()
            clean_filename = re.sub(r"[^a-zA-Z0-9_\-\.]", "", filename)
            file_path = os.path.join(DOWNLOADS_DIR, clean_filename)
            if not os.path.isfile(file_path):
                self.send_response(404)
                self.send_header("Connection", "close")
                self.end_headers()
                self.close_connection = True
                return
            try:
                file_size = os.path.getsize(file_path)
                if clean_filename.endswith(".apk"):
                    mime = "application/vnd.android.package-archive"
                elif clean_filename.endswith(".zip"):
                    mime = "application/zip"
                elif clean_filename.endswith(".dmg"):
                    mime = "application/x-apple-diskimage"
                else:
                    mime = "application/octet-stream"
                self.send_response(200)
                self.send_header("Content-Type", mime)
                self.send_header("Content-Length", str(file_size))
                self.send_header("Content-Disposition", f"attachment; filename=\"{clean_filename}\"")
                self.send_header("Access-Control-Allow-Origin", "*")
                self.send_header("Connection", "close")
                self.end_headers()
                self.close_connection = True
                return
            except Exception as e:
                self.send_response(500)
                self.end_headers()
                self.close_connection = True
                return

        if parsed_path in ("/proxy", "/api/comics8/proxy", "/proxy/proxy", "/api/comics8/proxy/proxy") or parsed_path.endswith("/proxy"):
            query = parse_qs(urlparse(self.path).query)
            target_url = query.get("url", [""])[0].strip()
            if not target_url or not (target_url.startswith("http://") or target_url.startswith("https://")):
                self.send_response(400)
                self.end_headers()
                self.close_connection = True
                return
            if not allowed_proxy_url(target_url):
                self.send_response(403)
                self.end_headers()
                self.close_connection = True
                return
            req_headers = self._proxy_forward_headers(query, include_accept=False)
            try:
                kind, resp = fetch_proxied("HEAD", target_url, req_headers, timeout=10, stream=False)
                if kind != "ok" or resp is None:
                    self.send_response(403 if kind == "forbidden" else 502)
                    self.end_headers()
                    self.close_connection = True
                    return
                try:
                    self.send_response(resp.status_code)
                    self._send_proxied_headers(resp)
                    self.end_headers()
                finally:
                    resp.close()
                self.close_connection = True
                return
            except Exception:
                self.send_response(502)
                self.end_headers()
                self.close_connection = True
                return

        self.send_response(404)
        self.end_headers()
        self.close_connection = True

    def do_OPTIONS(self):
        self.send_response(204)
        self.send_header("Connection", "close")
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
        self.send_header("Access-Control-Allow-Headers", CORS_ALLOW_HEADERS)
        self.end_headers()
        self.close_connection = True

    def do_GET(self):
        raw_path = self.path.split("?")[0].rstrip("/")
        parsed_path = raw_path if raw_path else "/"
        if parsed_path in ("/health", "/api/comics8/health"):
            self._send_json(200, {
                "status": "ok",
                "service": "comics8-sync",
                "time": int(time.time() * 1000),
                "syncSchema": SYNC_SCHEMA_CURRENT,
            })
            return

        if parsed_path in ("/proxy", "/api/comics8/proxy", "/proxy/proxy", "/api/comics8/proxy/proxy") or parsed_path.endswith("/proxy"):
            query = parse_qs(urlparse(self.path).query)
            target_url = query.get("url", [""])[0].strip()
            if not target_url or not (target_url.startswith("http://") or target_url.startswith("https://")):
                self._send_json(400, {"error": "BadRequest", "message": "Missing or invalid 'url' parameter (must start with http:// or https://)"})
                return
            if not allowed_proxy_url(target_url):
                self._send_json(403, {"error": "Forbidden", "message": "Host not allowed"})
                return

            req_headers = self._proxy_forward_headers(query, include_accept=True)
            try:
                kind, resp = fetch_proxied("GET", target_url, req_headers, timeout=20, stream=True)
                if kind == "forbidden":
                    self._send_json(403, {"error": "Forbidden", "message": "Host not allowed"})
                    return
                if kind != "ok" or resp is None:
                    self._send_json(502, {"error": "BadGateway", "message": "Proxy request failed"})
                    return
                try:
                    self.send_response(resp.status_code)
                    self._send_proxied_headers(resp)
                    self.end_headers()
                    self._write_proxied_body(resp)
                finally:
                    resp.close()
                self.close_connection = True
                return
            except TimeoutError:
                self._send_json(504, {"error": "GatewayTimeout", "message": "Target server timed out"})
                return
            except Exception as e:
                self._send_json(502, {"error": "BadGateway", "message": f"Proxy request failed: {e}"})
                return

        if parsed_path in ("/version", "/api/comics8/version"):
            if os.path.isfile(VERSION_FILE):
                try:
                    with open(VERSION_FILE, "r", encoding="utf-8") as vf:
                        vdata = json.load(vf)
                    self._send_json(200, vdata)
                    return
                except Exception as e:
                    print(f"Error reading version.json: {e}")
            default_version = {
                "android": {
                    "versionCode": 1,
                    "versionName": "1.0.0",
                    "downloadUrl": "/api/comics8/download/comics8-latest.apk",
                    "releaseNotes": "ì´ê¸° ë¦´ë¦¬ì¦"
                },
                "desktop": {
                    "versionCode": 1,
                    "versionName": "1.0.0",
                    "downloadUrl": "/api/comics8/download/Comics8-mac.zip",
                    "releaseNotes": "ì´ê¸° ë¦´ë¦¬ì¦"
                }
            }
            self._send_json(200, default_version)
            return

        if "/download/" in parsed_path:
            filename = parsed_path.split("/download/")[-1].strip()
            clean_filename = re.sub(r"[^a-zA-Z0-9_\-\.]", "", filename)
            file_path = os.path.join(DOWNLOADS_DIR, clean_filename)
            if not os.path.isfile(file_path):
                self._send_json(404, {"error": "Not Found", "message": f"File {clean_filename} not found"})
                return
            try:
                file_size = os.path.getsize(file_path)
                if clean_filename.endswith(".apk"):
                    mime = "application/vnd.android.package-archive"
                elif clean_filename.endswith(".zip"):
                    mime = "application/zip"
                elif clean_filename.endswith(".dmg"):
                    mime = "application/x-apple-diskimage"
                else:
                    mime = "application/octet-stream"
                self.send_response(200)
                self.send_header("Content-Type", mime)
                self.send_header("Content-Length", str(file_size))
                self.send_header("Content-Disposition", f"attachment; filename=\"{clean_filename}\"")
                self.send_header("Access-Control-Allow-Origin", "*")
                self.send_header("Connection", "close")
                self.end_headers()
                with open(file_path, "rb") as f:
                    while chunk := f.read(65536):
                        self.wfile.write(chunk)
                self.close_connection = True
                return
            except Exception as e:
                print(f"Error streaming file: {e}")
                return

        if parsed_path in ("/sync", "/api/comics8/sync"):
            key = self._get_sync_key()
            if not key:
                self._send_json(401, {"error": "Unauthorized", "message": "Missing sync key"})
                return

            user_id = get_safe_user_id(key)
            db_path = get_user_db_path(user_id)
            conn = sqlite3.connect(db_path)
            try:
                init_user_db(conn, user_id)
                snapshot = export_full_snapshot(conn)
                self._send_json(200, snapshot)
            finally:
                conn.close()
            return

        self._send_json(404, {"error": "Not Found"})

    def do_POST(self):
        raw_path = self.path.split("?")[0].rstrip("/")
        parsed_path = raw_path if raw_path else "/"
        now_ms = int(time.time() * 1000)

        # 1. Delta Sync Endpoint (/sync/delta or /api/comics8/sync/delta)
        if parsed_path in ("/sync/delta", "/api/comics8/sync/delta"):
            key = self._get_sync_key()
            if not key:
                self._send_json(401, {"error": "Unauthorized", "message": "Missing sync key"})
                return

            try:
                content_len = int(self.headers.get("Content-Length", 0))
                body = self.rfile.read(content_len).decode("utf-8") if content_len > 0 else "{}"
                req_json = json.loads(body) if body.strip() else {}

                since = int(req_json.get("since", 0))
                changes = req_json.get("changes", {})

                user_id = get_safe_user_id(key)
                db_path = get_user_db_path(user_id)
                conn = sqlite3.connect(db_path)
                try:
                    init_user_db(conn, user_id)
                    apply_delta_changes(conn, changes, now_ms)
                    server_changes = collect_delta_changes(conn, since)

                    self._send_json(200, {
                        "status": "success",
                        "serverTime": now_ms,
                        "userId": user_id,
                        "changes": server_changes,
                    })
                finally:
                    conn.close()
                return
            except Exception as e:
                self._send_json(400, {"error": "DeltaSyncError", "message": str(e)})
                return

        # 2. Legacy Full Snapshot POST Endpoint (/sync or /api/comics8/sync)
        if parsed_path in ("/sync", "/api/comics8/sync"):
            key = self._get_sync_key()
            if not key:
                self._send_json(401, {"error": "Unauthorized", "message": "Missing sync key"})
                return

            try:
                content_len = int(self.headers.get("Content-Length", 0))
                if content_len <= 0:
                    self._send_json(400, {"error": "BadRequest", "message": "Empty body"})
                    return
                body = self.rfile.read(content_len).decode("utf-8")
                parsed_json = json.loads(body)

                user_id = get_safe_user_id(key)
                db_path = get_user_db_path(user_id)
                conn = sqlite3.connect(db_path)
                try:
                    init_user_db(conn, user_id)
                    import_full_snapshot(conn, parsed_json, now_ms)
                finally:
                    conn.close()

                self._send_json(200, {
                    "status": "success",
                    "savedAt": now_ms,
                    "userId": user_id,
                    "sizeBytes": len(body.encode("utf-8")),
                    "favorites": len(parsed_json.get("favorites", [])),
                    "history": len(parsed_json.get("history", [])),
                })
                return
            except Exception as e:
                self._send_json(400, {"error": "SaveError", "message": str(e)})
                return

        # 3. Pairing Code Request Endpoint (/pair/request or /api/comics8/pair/request)
        if parsed_path in ("/pair/request", "/api/comics8/pair/request"):
            key = self._get_sync_key()
            if not key:
                self._send_json(401, {"success": False, "message": "Missing sync key"})
                return
            code = request_pair_code(key, ttl_seconds=300)
            self._send_json(200, {
                "success": True,
                "code": code,
                "expiresInSeconds": 300,
            })
            return

        # 4. Pairing Code Confirm Endpoint (/pair/confirm or /api/comics8/pair/confirm)
        if parsed_path in ("/pair/confirm", "/api/comics8/pair/confirm"):
            try:
                content_len = int(self.headers.get("Content-Length", 0))
                body = self.rfile.read(content_len).decode("utf-8") if content_len > 0 else "{}"
                req_json = json.loads(body) if body.strip() else {}
                code = str(req_json.get("code", "")).strip()
                if not code:
                    self._send_json(400, {"success": False, "message": "페어링 코드를 입력해주세요."})
                    return
                sync_key = confirm_pair_code(code)
                if not sync_key:
                    self._send_json(400, {"success": False, "message": "유효하지 않거나 만료된 페어링 코드입니다."})
                    return
                self._send_json(200, {
                    "success": True,
                    "syncKey": sync_key,
                })
                return
            except Exception as e:
                self._send_json(400, {"success": False, "message": str(e)})
                return

        self._send_json(404, {"error": "Not Found"})

def run():
    import signal, threading
    os.makedirs(DATA_DIR, exist_ok=True)
    server = ThreadingHTTPServer(("0.0.0.0", PORT), SyncHandler)
    server.daemon_threads = True

    def handle_signal(sig, frame):
        print(f"Received signal {sig}, shutting down server gracefully...")
        threading.Thread(target=server.shutdown, daemon=True).start()

    signal.signal(signal.SIGTERM, handle_signal)
    signal.signal(signal.SIGINT, handle_signal)

    print(f"Comics8 Multi-User Sync Server listening on 0.0.0.0:{PORT} (data dir: {DATA_DIR})")
    try:
        server.serve_forever()
    finally:
        server.server_close()
        print("Server stopped cleanly.")

if __name__ == "__main__":
    run()
