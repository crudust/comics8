import os
import json
import time
import sqlite3
import hashlib
import re
import requests
from http.server import ThreadingHTTPServer, BaseHTTPRequestHandler
from urllib.parse import parse_qs, urlparse

DATA_DIR = os.environ.get("DATA_DIR", os.path.expanduser("~/comics8-sync/data"))
DOWNLOADS_DIR = os.path.join(DATA_DIR, "downloads")
VERSION_FILE = os.path.join(DATA_DIR, "version.json")
os.makedirs(DOWNLOADS_DIR, exist_ok=True)
PORT = int(os.environ.get("PORT", "8905"))

def get_safe_user_id(key: str) -> str:
    cleaned = re.sub(r'[^a-zA-Z0-9_\-]', '', key.strip())
    if 3 <= len(cleaned) <= 64:
        return cleaned
    return hashlib.sha256(key.strip().encode('utf-8')).hexdigest()[:24]

def get_user_db_path(user_id: str) -> str:
    os.makedirs(DATA_DIR, exist_ok=True)
    return os.path.join(DATA_DIR, f"user_{user_id}.db")

def init_user_db(conn: sqlite3.Connection, user_id: str):
    with conn:
        conn.execute("""
        CREATE TABLE IF NOT EXISTS favorites (
            id TEXT PRIMARY KEY,
            title TEXT,
            thumbUrl TEXT,
            href TEXT,
            genre TEXT,
            updatedAt TEXT,
            savedAt INTEGER,
            syncUpdatedAt INTEGER
        );
        """)
        conn.execute("CREATE INDEX IF NOT EXISTS idx_fav_sync ON favorites(syncUpdatedAt);")

        conn.execute("""
        CREATE TABLE IF NOT EXISTS history (
            toonId TEXT PRIMARY KEY,
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
            syncUpdatedAt INTEGER
        );
        """)
        conn.execute("CREATE INDEX IF NOT EXISTS idx_hist_sync ON history(syncUpdatedAt);")

        conn.execute("""
        CREATE TABLE IF NOT EXISTS read_episodes (
            toonId TEXT,
            wrId TEXT,
            readAt INTEGER,
            lastPage INTEGER DEFAULT 0,
            syncUpdatedAt INTEGER,
            PRIMARY KEY (toonId, wrId)
        );
        """)
        conn.execute("CREATE INDEX IF NOT EXISTS idx_ep_sync ON read_episodes(syncUpdatedAt);")

        conn.execute("""
        CREATE TABLE IF NOT EXISTS reader_settings (
            toonId TEXT PRIMARY KEY,
            viewMode TEXT,
            readDirection TEXT,
            updatedAt INTEGER,
            syncUpdatedAt INTEGER
        );
        """)
        conn.execute("CREATE INDEX IF NOT EXISTS idx_set_sync ON reader_settings(syncUpdatedAt);")

        conn.execute("""
        CREATE TABLE IF NOT EXISTS tombstones (
            entityType TEXT,
            entityId TEXT,
            deletedAt INTEGER,
            PRIMARY KEY (entityType, entityId)
        );
        """)
        conn.execute("CREATE INDEX IF NOT EXISTS idx_tomb_deleted ON tombstones(deletedAt);")

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
                import_full_snapshot(conn, data, now_ms=int(time.time() * 1000))
            except Exception as e:
                print(f"Error migrating legacy JSON for {user_id}: {e}")

def import_full_snapshot(conn: sqlite3.Connection, data: dict, now_ms: int):
    with conn:
        favs = data.get("favorites", [])
        for f in favs:
            saved_at = f.get("savedAt") or now_ms
            conn.execute("""
            INSERT INTO favorites (id, title, thumbUrl, href, genre, updatedAt, savedAt, syncUpdatedAt)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(id) DO UPDATE SET
                title = excluded.title,
                thumbUrl = excluded.thumbUrl,
                href = excluded.href,
                genre = excluded.genre,
                updatedAt = excluded.updatedAt,
                savedAt = excluded.savedAt,
                syncUpdatedAt = excluded.syncUpdatedAt
            """, (f.get("id"), f.get("title", ""), f.get("thumbUrl", ""), f.get("href", ""),
                  f.get("genre", ""), f.get("updatedAt"), saved_at, now_ms))

        hist = data.get("history", [])
        for h in hist:
            last_read_at = h.get("lastReadAt") or now_ms
            conn.execute("""
            INSERT INTO history (toonId, toonTitle, toonThumbUrl, toonHref, lastWrId, lastEpisodeTitle, lastEpisodeHref,
                                 lastReadOrder, totalEpisodes, lastReadAt, nextWrId, nextEpisodeTitle, nextEpisodeHref, hasNew, syncUpdatedAt)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(toonId) DO UPDATE SET
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
            """, (h.get("toonId"), h.get("toonTitle", ""), h.get("toonThumbUrl", ""), h.get("toonHref", ""),
                  h.get("lastWrId", ""), h.get("lastEpisodeTitle", ""), h.get("lastEpisodeHref", ""),
                  h.get("lastReadOrder", 1), h.get("totalEpisodes", 1), last_read_at,
                  h.get("nextWrId"), h.get("nextEpisodeTitle"), h.get("nextEpisodeHref"),
                  1 if h.get("hasNew") else 0, now_ms))

        eps = data.get("readEpisodes", [])
        for ep in eps:
            read_at = ep.get("readAt") or now_ms
            conn.execute("""
            INSERT INTO read_episodes (toonId, wrId, readAt, lastPage, syncUpdatedAt)
            VALUES (?, ?, ?, ?, ?)
            ON CONFLICT(toonId, wrId) DO UPDATE SET
                readAt = excluded.readAt,
                lastPage = excluded.lastPage,
                syncUpdatedAt = excluded.syncUpdatedAt
            """, (ep.get("toonId"), ep.get("wrId"), read_at, ep.get("lastPage", 0), now_ms))

        settings = data.get("readerSettings", [])
        for s in settings:
            up_at = s.get("updatedAt") or now_ms
            conn.execute("""
            INSERT INTO reader_settings (toonId, viewMode, readDirection, updatedAt, syncUpdatedAt)
            VALUES (?, ?, ?, ?, ?)
            ON CONFLICT(toonId) DO UPDATE SET
                viewMode = excluded.viewMode,
                readDirection = excluded.readDirection,
                updatedAt = excluded.updatedAt,
                syncUpdatedAt = excluded.syncUpdatedAt
            """, (s.get("toonId"), s.get("viewMode", "SCROLL"), s.get("readDirection", "RIGHT_TO_LEFT"), up_at, now_ms))

def export_full_snapshot(conn: sqlite3.Connection) -> dict:
    conn.row_factory = sqlite3.Row
    cursor = conn.cursor()

    favs = []
    for row in cursor.execute("SELECT id, title, thumbUrl, href, genre, updatedAt, savedAt FROM favorites ORDER BY savedAt DESC"):
        favs.append(dict(row))

    hist = []
    for row in cursor.execute("SELECT toonId, toonTitle, toonThumbUrl, toonHref, lastWrId, lastEpisodeTitle, lastEpisodeHref, lastReadOrder, totalEpisodes, lastReadAt, nextWrId, nextEpisodeTitle, nextEpisodeHref, hasNew FROM history ORDER BY lastReadAt DESC"):
        d = dict(row)
        d["hasNew"] = bool(d.get("hasNew"))
        hist.append(d)

    eps = []
    for row in cursor.execute("SELECT toonId, wrId, readAt, lastPage FROM read_episodes"):
        eps.append(dict(row))

    settings = []
    for row in cursor.execute("SELECT toonId, viewMode, readDirection, updatedAt FROM reader_settings"):
        settings.append(dict(row))

    return {
        "version": 1,
        "appName": "Comics8",
        "exportedAt": int(time.time() * 1000),
        "favorites": favs,
        "history": hist,
        "readEpisodes": eps,
        "readerSettings": settings,
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
            self.send_header("Access-Control-Allow-Headers", "Authorization, Content-Type, X-Sync-Key")
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
            req_headers = {
                "User-Agent": self.headers.get("User-Agent", "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"),
                "Referer": "http://103.204.13.68:8904",
            }
            try:
                resp = requests.head(target_url, headers=req_headers, timeout=10, allow_redirects=True)
                self.send_response(resp.status_code)
                self.send_header("Content-Type", resp.headers.get("Content-Type", "text/html"))
                self.send_header("Access-Control-Allow-Origin", "*")
                self.send_header("Connection", "close")
                self.end_headers()
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
        self.send_header("Access-Control-Allow-Headers", "Authorization, Content-Type, X-Sync-Key")
        self.end_headers()
        self.close_connection = True

    def do_GET(self):
        raw_path = self.path.split("?")[0].rstrip("/")
        parsed_path = raw_path if raw_path else "/"
        if parsed_path in ("/health", "/api/comics8/health"):
            self._send_json(200, {"status": "ok", "service": "comics8-sync", "time": int(time.time() * 1000)})
            return

        if parsed_path in ("/proxy", "/api/comics8/proxy", "/proxy/proxy", "/api/comics8/proxy/proxy") or parsed_path.endswith("/proxy"):
            query = parse_qs(urlparse(self.path).query)
            target_url = query.get("url", [""])[0].strip()
            if not target_url or not (target_url.startswith("http://") or target_url.startswith("https://")):
                self._send_json(400, {"error": "BadRequest", "message": "Missing or invalid 'url' parameter (must start with http:// or https://)"})
                return

            req_headers = {
                "User-Agent": self.headers.get("User-Agent", "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"),
                "Accept": self.headers.get("Accept", "text/html,application/xhtml+xml;q=0.9,*/*;q=0.8"),
                "Accept-Language": self.headers.get("Accept-Language", "ko-KR,ko;q=0.9,en;q=0.8"),
                "Referer": "http://103.204.13.68:8904",
            }
            try:
                resp = requests.get(target_url, headers=req_headers, timeout=20, allow_redirects=True, stream=True)
                self.send_response(resp.status_code)
                content_type = resp.headers.get("Content-Type", "text/html; charset=utf-8")
                self.send_header("Content-Type", content_type)
                self.send_header("Access-Control-Allow-Origin", "*")
                self.send_header("Connection", "close")
                self.end_headers()
                for chunk in resp.iter_content(chunk_size=65536):
                    if chunk:
                        self.wfile.write(chunk)
                self.close_connection = True
                return
            except requests.exceptions.Timeout:
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

                    with conn:
                        # 1. Process client tombstones
                        tombstones = changes.get("tombstones", [])
                        for t in tombstones:
                            e_type = t.get("entityType", "").upper()
                            e_id = t.get("entityId", "")
                            del_at = int(t.get("deletedAt", now_ms))
                            conn.execute("""
                            INSERT INTO tombstones (entityType, entityId, deletedAt)
                            VALUES (?, ?, ?)
                            ON CONFLICT(entityType, entityId) DO UPDATE SET deletedAt = MAX(deletedAt, excluded.deletedAt)
                            """, (e_type, e_id, del_at))
                            if e_type == "FAVORITE":
                                conn.execute("DELETE FROM favorites WHERE id = ?", (e_id,))
                            elif e_type == "HISTORY":
                                conn.execute("DELETE FROM history WHERE toonId = ?", (e_id,))
                            elif e_type == "EPISODE":
                                conn.execute("DELETE FROM read_episodes WHERE toonId = ?", (e_id,))

                        # 2. Process client favorites
                        for f in changes.get("favorites", []):
                            f_id = f.get("id")
                            saved_at = int(f.get("savedAt", now_ms))
                            conn.execute("""
                            INSERT INTO favorites (id, title, thumbUrl, href, genre, updatedAt, savedAt, syncUpdatedAt)
                            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                            ON CONFLICT(id) DO UPDATE SET
                                title = excluded.title,
                                thumbUrl = excluded.thumbUrl,
                                href = excluded.href,
                                genre = excluded.genre,
                                updatedAt = excluded.updatedAt,
                                savedAt = excluded.savedAt,
                                syncUpdatedAt = ?
                            WHERE excluded.savedAt >= favorites.savedAt
                            """, (f_id, f.get("title", ""), f.get("thumbUrl", ""), f.get("href", ""),
                                  f.get("genre", ""), f.get("updatedAt"), saved_at, now_ms, now_ms))
                            conn.execute("DELETE FROM tombstones WHERE entityType = 'FAVORITE' AND entityId = ? AND deletedAt < ?", (f_id, saved_at))

                        # 3. Process client history
                        for h in changes.get("history", []):
                            toon_id = h.get("toonId")
                            last_read_at = int(h.get("lastReadAt", now_ms))
                            conn.execute("""
                            INSERT INTO history (toonId, toonTitle, toonThumbUrl, toonHref, lastWrId, lastEpisodeTitle, lastEpisodeHref,
                                                 lastReadOrder, totalEpisodes, lastReadAt, nextWrId, nextEpisodeTitle, nextEpisodeHref, hasNew, syncUpdatedAt)
                            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                            ON CONFLICT(toonId) DO UPDATE SET
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
                            """, (toon_id, h.get("toonTitle", ""), h.get("toonThumbUrl", ""), h.get("toonHref", ""),
                                  h.get("lastWrId", ""), h.get("lastEpisodeTitle", ""), h.get("lastEpisodeHref", ""),
                                  h.get("lastReadOrder", 1), h.get("totalEpisodes", 1), last_read_at,
                                  h.get("nextWrId"), h.get("nextEpisodeTitle"), h.get("nextEpisodeHref"),
                                  1 if h.get("hasNew") else 0, now_ms, now_ms))
                            conn.execute("DELETE FROM tombstones WHERE entityType = 'HISTORY' AND entityId = ? AND deletedAt < ?", (toon_id, last_read_at))

                        # 4. Process client readEpisodes
                        for ep in changes.get("readEpisodes", []):
                            toon_id = ep.get("toonId")
                            wr_id = ep.get("wrId")
                            read_at = int(ep.get("readAt", now_ms))
                            conn.execute("""
                            INSERT INTO read_episodes (toonId, wrId, readAt, lastPage, syncUpdatedAt)
                            VALUES (?, ?, ?, ?, ?)
                            ON CONFLICT(toonId, wrId) DO UPDATE SET
                                readAt = excluded.readAt,
                                lastPage = excluded.lastPage,
                                syncUpdatedAt = ?
                            WHERE excluded.readAt >= read_episodes.readAt
                            """, (toon_id, wr_id, read_at, ep.get("lastPage", 0), now_ms, now_ms))

                        # 5. Process client readerSettings
                        for s in changes.get("readerSettings", []):
                            toon_id = s.get("toonId")
                            up_at = int(s.get("updatedAt", now_ms))
                            conn.execute("""
                            INSERT INTO reader_settings (toonId, viewMode, readDirection, updatedAt, syncUpdatedAt)
                            VALUES (?, ?, ?, ?, ?)
                            ON CONFLICT(toonId) DO UPDATE SET
                                viewMode = excluded.viewMode,
                                readDirection = excluded.readDirection,
                                updatedAt = excluded.updatedAt,
                                syncUpdatedAt = ?
                            WHERE excluded.updatedAt >= reader_settings.updatedAt
                            """, (toon_id, s.get("viewMode", "SCROLL"), s.get("readDirection", "RIGHT_TO_LEFT"), up_at, now_ms, now_ms))

                    # Query server changes since `since`
                    conn.row_factory = sqlite3.Row
                    cur = conn.cursor()

                    server_favs = [dict(r) for r in cur.execute("SELECT id, title, thumbUrl, href, genre, updatedAt, savedAt FROM favorites WHERE syncUpdatedAt > ?", (since,))]
                    server_hist = []
                    for r in cur.execute("SELECT toonId, toonTitle, toonThumbUrl, toonHref, lastWrId, lastEpisodeTitle, lastEpisodeHref, lastReadOrder, totalEpisodes, lastReadAt, nextWrId, nextEpisodeTitle, nextEpisodeHref, hasNew FROM history WHERE syncUpdatedAt > ?", (since,)):
                        d = dict(r)
                        d["hasNew"] = bool(d.get("hasNew"))
                        server_hist.append(d)
                    server_eps = [dict(r) for r in cur.execute("SELECT toonId, wrId, readAt, lastPage FROM read_episodes WHERE syncUpdatedAt > ?", (since,))]
                    server_settings = [dict(r) for r in cur.execute("SELECT toonId, viewMode, readDirection, updatedAt FROM reader_settings WHERE syncUpdatedAt > ?", (since,))]
                    server_tombs = [dict(r) for r in cur.execute("SELECT entityType, entityId, deletedAt FROM tombstones WHERE deletedAt > ?", (since,))]

                    self._send_json(200, {
                        "status": "success",
                        "serverTime": now_ms,
                        "userId": user_id,
                        "changes": {
                            "favorites": server_favs,
                            "history": server_hist,
                            "readEpisodes": server_eps,
                            "readerSettings": server_settings,
                            "tombstones": server_tombs,
                        }
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

        self._send_json(404, {"error": "Not Found"})

def run():
    os.makedirs(DATA_DIR, exist_ok=True)
    server = ThreadingHTTPServer(("0.0.0.0", PORT), SyncHandler)
    print(f"Comics8 Multi-User Sync Server listening on 0.0.0.0:{PORT} (data dir: {DATA_DIR})")
    server.serve_forever()

if __name__ == "__main__":
    run()


