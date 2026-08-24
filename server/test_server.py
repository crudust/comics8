from __future__ import annotations

import os
import sqlite3
import sys
import unittest

sys.path.insert(0, os.path.dirname(__file__))
from server import (
    DEFAULT_REFERER,
    apply_delta_changes,
    build_proxy_forward_headers,
    collect_delta_changes,
    emit_favorite,
    emit_tombstone,
    emit_work_row,
    export_full_snapshot,
    import_full_snapshot,
    init_user_db,
    is_allowed_proxy_host,
    migrate_user_db,
    next_proxy_url,
    parse_entity_id,
    passthrough_proxy_headers,
)


def create_legacy_user_db() -> sqlite3.Connection:
    conn = sqlite3.connect(":memory:")
    conn.execute("""
    CREATE TABLE favorites (
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
    conn.execute("""
    CREATE TABLE history (
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
    conn.execute("""
    CREATE TABLE read_episodes (
        toonId TEXT,
        wrId TEXT,
        readAt INTEGER,
        lastPage INTEGER DEFAULT 0,
        syncUpdatedAt INTEGER,
        PRIMARY KEY (toonId, wrId)
    );
    """)
    conn.execute("""
    CREATE TABLE reader_settings (
        toonId TEXT PRIMARY KEY,
        viewMode TEXT,
        readDirection TEXT,
        updatedAt INTEGER,
        syncUpdatedAt INTEGER
    );
    """)
    conn.execute("""
    CREATE TABLE tombstones (
        entityType TEXT,
        entityId TEXT,
        deletedAt INTEGER,
        PRIMARY KEY (entityType, entityId)
    );
    """)
    conn.execute(
        "INSERT INTO favorites (id, title, thumbUrl, href, genre, updatedAt, savedAt, syncUpdatedAt) "
        "VALUES ('100', 'Legacy Fav', '', '', '', NULL, 10, 10)"
    )
    conn.execute(
        "INSERT INTO history (toonId, toonTitle, toonThumbUrl, toonHref, lastWrId, lastEpisodeTitle, lastEpisodeHref, "
        "lastReadOrder, totalEpisodes, lastReadAt, nextWrId, nextEpisodeTitle, nextEpisodeHref, hasNew, syncUpdatedAt) "
        "VALUES ('100', 'Legacy Hist', '', '', '1', 'ep', '', 1, 2, 20, NULL, NULL, NULL, 0, 20)"
    )
    conn.execute(
        "INSERT INTO read_episodes (toonId, wrId, readAt, lastPage, syncUpdatedAt) VALUES ('100', '1', 20, 3, 20)"
    )
    conn.execute(
        "INSERT INTO reader_settings (toonId, viewMode, readDirection, updatedAt, syncUpdatedAt) "
        "VALUES ('100', 'SCROLL', 'RIGHT_TO_LEFT', 20, 20)"
    )
    conn.execute("INSERT INTO tombstones (entityType, entityId, deletedAt) VALUES ('FAVORITE', '200', 30)")
    conn.commit()
    return conn


class MigrateUserDbTest(unittest.TestCase):
    def test_rebuilds_legacy_tables_and_prefixes_tombstones(self):
        conn = create_legacy_user_db()
        migrate_user_db(conn)

        self.assertEqual(conn.execute("PRAGMA user_version").fetchone()[0], 2)
        fav_cols = {row[1] for row in conn.execute("PRAGMA table_info(favorites)")}
        self.assertIn("sourceId", fav_cols)
        self.assertIn("toonId", fav_cols)
        self.assertNotIn("id", fav_cols)

        fav = conn.execute("SELECT sourceId, toonId, title FROM favorites").fetchone()
        self.assertEqual(fav, ("eleven", "100", "Legacy Fav"))
        hist = conn.execute("SELECT sourceId, toonId FROM history").fetchone()
        self.assertEqual(hist, ("eleven", "100"))
        ep = conn.execute("SELECT sourceId, toonId, wrId FROM read_episodes").fetchone()
        self.assertEqual(ep, ("eleven", "100", "1"))
        setting = conn.execute("SELECT sourceId, toonId FROM reader_settings").fetchone()
        self.assertEqual(setting, ("eleven", "100"))
        tomb = conn.execute("SELECT entityId FROM tombstones").fetchone()
        self.assertEqual(tomb[0], "eleven:200")

        pk = {row[5] for row in conn.execute("PRAGMA table_info(favorites)") if row[5]}
        self.assertEqual(pk, {1, 2})

    def test_fresh_init_sets_user_version_without_clobbering(self):
        conn = sqlite3.connect(":memory:")
        init_user_db(conn, "tester")
        self.assertEqual(conn.execute("PRAGMA user_version").fetchone()[0], 2)
        fav_cols = {row[1] for row in conn.execute("PRAGMA table_info(favorites)")}
        self.assertIn("sourceId", fav_cols)
        migrate_user_db(conn)
        self.assertEqual(conn.execute("PRAGMA user_version").fetchone()[0], 2)

    def test_init_user_db_rebuilds_legacy_schema(self):
        conn = create_legacy_user_db()
        init_user_db(conn, "legacy")

        self.assertEqual(conn.execute("PRAGMA user_version").fetchone()[0], 2)
        fav_cols = {row[1] for row in conn.execute("PRAGMA table_info(favorites)")}
        self.assertIn("sourceId", fav_cols)
        self.assertIn("toonId", fav_cols)
        self.assertNotIn("id", fav_cols)
        fav = conn.execute("SELECT sourceId, toonId, title FROM favorites").fetchone()
        self.assertEqual(fav, ("eleven", "100", "Legacy Fav"))
        self.assertEqual(conn.execute("SELECT entityId FROM tombstones").fetchone()[0], "eleven:200")
        pk = {row[5] for row in conn.execute("PRAGMA table_info(favorites)") if row[5]}
        self.assertEqual(pk, {1, 2})


class SyncSnapshotAndDeltaTest(unittest.TestCase):
    def test_emit_favorite_includes_source_id_and_id(self):
        fav = emit_favorite({"sourceId": "hitomi", "id": "9", "title": "A", "savedAt": 1})
        self.assertEqual(fav["sourceId"], "hitomi")
        self.assertEqual(fav["id"], "9")
        self.assertNotIn("toonId", fav)

    def test_emit_work_row_includes_source_id(self):
        hist = emit_work_row({"sourceId": "eleven", "toonId": "9", "hasNew": 1})
        self.assertEqual(hist["sourceId"], "eleven")
        self.assertEqual(hist["toonId"], "9")
        self.assertTrue(hist["hasNew"])

    def test_emit_tombstone_emits_entity_id(self):
        tomb = emit_tombstone({"entityType": "FAVORITE", "entityId": "hitomi:123", "deletedAt": 1})
        self.assertEqual(tomb["entityType"], "FAVORITE")
        self.assertEqual(tomb["entityId"], "hitomi:123")

    def test_export_and_delta_sync_all_sources(self):
        conn = sqlite3.connect(":memory:")
        init_user_db(conn, "tester")
        import_full_snapshot(conn, {
            "favorites": [
                {"sourceId": "eleven", "id": "1", "title": "Eleven", "savedAt": 1},
                {"sourceId": "hitomi", "id": "2", "title": "Hitomi", "savedAt": 1},
            ],
            "history": [
                {"sourceId": "eleven", "toonId": "1", "lastReadAt": 1},
                {"sourceId": "hitomi", "toonId": "2", "lastReadAt": 1},
            ],
            "readEpisodes": [
                {"sourceId": "eleven", "toonId": "1", "wrId": "a", "readAt": 1},
                {"sourceId": "hitomi", "toonId": "2", "wrId": "b", "readAt": 1},
            ],
            "readerSettings": [
                {"sourceId": "hitomi", "toonId": "2", "viewMode": "SINGLE", "updatedAt": 1},
            ],
        }, now_ms=5)

        snapshot = export_full_snapshot(conn)
        self.assertEqual(snapshot["version"], 2)
        self.assertEqual({(f["sourceId"], f["id"]) for f in snapshot["favorites"]}, {("eleven", "1"), ("hitomi", "2")})
        self.assertEqual({(h["sourceId"], h["toonId"]) for h in snapshot["history"]}, {("eleven", "1"), ("hitomi", "2")})

        apply_delta_changes(conn, {
            "favorites": [{"sourceId": "hitomi", "id": "99", "title": "Hitomi 99", "savedAt": 9}],
            "tombstones": [{"entityType": "FAVORITE", "entityId": "hitomi:2", "deletedAt": 9}],
        }, now_ms=9)

        remaining_favs = {(r[0], r[1]) for r in conn.execute("SELECT sourceId, toonId FROM favorites")}
        self.assertEqual(remaining_favs, {("eleven", "1"), ("hitomi", "99")})

        changes = collect_delta_changes(conn, since=5)
        self.assertEqual({(f["sourceId"], f["id"]) for f in changes["favorites"]}, {("hitomi", "99")})
        self.assertEqual({t["entityId"] for t in changes["tombstones"]}, {"hitomi:2"})


class ProxyAllowlistTest(unittest.TestCase):
    def test_suffix_safe_hosts(self):
        self.assertTrue(is_allowed_proxy_host("aa.gold-usergeneratedcontent.net"))
        self.assertFalse(is_allowed_proxy_host("notevilgold-usergeneratedcontent.net"))
        self.assertTrue(is_allowed_proxy_host("hitomi.la"))
        self.assertTrue(is_allowed_proxy_host("tn.hitomi.la"))
        self.assertTrue(is_allowed_proxy_host("www.pl4050.com"))
        self.assertTrue(is_allowed_proxy_host("11toon8.com"))
        self.assertTrue(is_allowed_proxy_host("103.204.13.68"))
        self.assertFalse(is_allowed_proxy_host("evil.example.com"))

    def test_redirects_stay_on_allowlist(self):
        self.assertEqual(
            next_proxy_url("https://11toon8.com/a", "https://www.pl4050.com/img.jpg"),
            "https://www.pl4050.com/img.jpg",
        )
        self.assertEqual(
            next_proxy_url("https://11toon8.com/a", "/covers/1.webp"),
            "https://11toon8.com/covers/1.webp",
        )
        self.assertIsNone(next_proxy_url("https://11toon8.com/a", "https://evil.example.com/steal"))
        self.assertIsNone(next_proxy_url("https://11toon8.com/a", "https://notevilgold-usergeneratedcontent.net/x"))

    def test_forward_range_and_referer_priority(self):
        query_wins = build_proxy_forward_headers(
            {"referer": ["https://hitomi.la/"]},
            {"Referer": "http://ignored.example", "Range": "bytes=0-99"},
        )
        self.assertEqual(query_wins["Referer"], "https://hitomi.la/")
        self.assertEqual(query_wins["Range"], "bytes=0-99")
        self.assertEqual(query_wins["Accept-Encoding"], "identity")

        header_referer = build_proxy_forward_headers({}, {"Referer": "https://tn.hitomi.la/g/1.html"})
        self.assertEqual(header_referer["Referer"], "https://tn.hitomi.la/g/1.html")
        self.assertNotIn("Range", header_referer)

        defaulted = build_proxy_forward_headers({}, {})
        self.assertEqual(defaulted["Referer"], DEFAULT_REFERER)

    def test_passthrough_skips_length_when_decoded(self):
        gzip_headers = {
            "Content-Type": "text/html",
            "Content-Encoding": "gzip",
            "Content-Length": "12",
            "Content-Range": "bytes 0-11/12",
        }
        decoded = dict(passthrough_proxy_headers(gzip_headers, decoded=True))
        self.assertEqual(decoded["Content-Type"], "text/html")
        self.assertNotIn("Content-Length", decoded)
        self.assertNotIn("Content-Range", decoded)
        self.assertNotIn("Content-Encoding", decoded)

        raw = dict(passthrough_proxy_headers(gzip_headers, decoded=False))
        self.assertEqual(raw["Content-Length"], "12")
        self.assertEqual(raw["Content-Range"], "bytes 0-11/12")
        self.assertEqual(raw["Content-Encoding"], "gzip")

    def test_passthrough_206_content_range(self):
        headers = dict(passthrough_proxy_headers({
            "Content-Type": "image/jpeg",
            "Content-Length": "100",
            "Content-Range": "bytes 0-99/800",
        }, decoded=False))
        self.assertEqual(headers["Content-Type"], "image/jpeg")
        self.assertEqual(headers["Content-Length"], "100")
        self.assertEqual(headers["Content-Range"], "bytes 0-99/800")


class EntityIdTest(unittest.TestCase):
    def test_unprefixed_is_eleven(self):
        self.assertEqual(parse_entity_id("123"), ("eleven", "123"))
        self.assertEqual(parse_entity_id("hitomi:55"), ("hitomi", "55"))


if __name__ == "__main__":
    unittest.main()
