from __future__ import annotations

import os
import sqlite3
import sys
import unittest

sys.path.insert(0, os.path.dirname(__file__))
from server import (
    apply_delta_changes,
    collect_delta_changes,
    emit_favorite,
    emit_tombstone,
    emit_work_row,
    export_full_snapshot,
    import_full_snapshot,
    init_user_db,
    is_allowed_proxy_host,
    migrate_user_db,
    parse_entity_id,
    parse_sync_schema,
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


class SchemaFilterTest(unittest.TestCase):
    def test_parse_sync_schema_defaults_to_one(self):
        self.assertEqual(parse_sync_schema(None), 1)
        self.assertEqual(parse_sync_schema(""), 1)
        self.assertEqual(parse_sync_schema("2"), 2)
        self.assertEqual(parse_sync_schema("nope"), 1)

    def test_schema1_strips_source_id_and_keeps_favorite_id(self):
        fav = emit_favorite({"sourceId": "eleven", "toonId": "9", "title": "A", "savedAt": 1}, schema=1)
        self.assertEqual(fav["id"], "9")
        self.assertNotIn("sourceId", fav)
        self.assertNotIn("toonId", fav)
        self.assertIsNone(emit_favorite({"sourceId": "hitomi", "toonId": "9", "title": "B"}, schema=1))

        hist = emit_work_row({"sourceId": "eleven", "toonId": "9", "hasNew": 1}, schema=1)
        self.assertNotIn("sourceId", hist)
        self.assertEqual(hist["toonId"], "9")
        self.assertTrue(hist["hasNew"])
        self.assertIsNone(emit_work_row({"sourceId": "hitomi", "toonId": "1"}, schema=1))

        tomb = emit_tombstone({"entityType": "FAVORITE", "entityId": "eleven:9", "deletedAt": 1}, schema=1)
        self.assertEqual(tomb["entityId"], "9")
        self.assertIsNone(emit_tombstone({"entityType": "FAVORITE", "entityId": "hitomi:1", "deletedAt": 1}, schema=1))

    def test_schema2_emits_source_id(self):
        fav = emit_favorite({"sourceId": "hitomi", "id": "9", "title": "A"}, schema=2)
        self.assertEqual(fav["sourceId"], "hitomi")
        self.assertEqual(fav["id"], "9")
        self.assertNotIn("toonId", fav)
        tomb = emit_tombstone({"entityType": "HISTORY", "entityId": "100", "deletedAt": 1}, schema=2)
        self.assertEqual(tomb["entityId"], "eleven:100")

    def test_export_and_delta_filter_schema1(self):
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
        }, now_ms=5, schema=2)

        schema1 = export_full_snapshot(conn, schema=1)
        self.assertEqual(schema1["version"], 1)
        self.assertEqual([f["id"] for f in schema1["favorites"]], ["1"])
        self.assertNotIn("sourceId", schema1["favorites"][0])
        self.assertEqual([h["toonId"] for h in schema1["history"]], ["1"])
        self.assertNotIn("sourceId", schema1["history"][0])

        schema2 = export_full_snapshot(conn, schema=2)
        self.assertEqual(schema2["version"], 2)
        self.assertEqual({(f["sourceId"], f["id"]) for f in schema2["favorites"]}, {("eleven", "1"), ("hitomi", "2")})

        apply_delta_changes(conn, {
            "favorites": [{"sourceId": "hitomi", "id": "99", "title": "Nope", "savedAt": 9}],
            "tombstones": [{"entityType": "FAVORITE", "entityId": "hitomi:2", "deletedAt": 9}],
        }, now_ms=9, schema=1)
        remaining = {(r[0], r[1]) for r in conn.execute("SELECT sourceId, toonId FROM favorites")}
        self.assertEqual(remaining, {("eleven", "1"), ("hitomi", "2")})

        apply_delta_changes(conn, {
            "tombstones": [{"entityType": "EPISODE", "entityId": "1", "deletedAt": 10}],
            "readEpisodes": [
                {"toonId": "1", "wrId": "a", "readAt": 10},
                {"sourceId": "hitomi", "toonId": "2", "wrId": "b", "readAt": 10},
            ],
        }, now_ms=10, schema=2)
        apply_delta_changes(conn, {
            "tombstones": [{"entityType": "EPISODE", "entityId": "1", "deletedAt": 11}],
        }, now_ms=11, schema=1)
        eps = [tuple(r) for r in conn.execute("SELECT sourceId, toonId, wrId FROM read_episodes")]
        self.assertEqual(eps, [("hitomi", "2", "b")])

        changes = collect_delta_changes(conn, since=0, schema=1)
        self.assertEqual([f["id"] for f in changes["favorites"]], ["1"])
        self.assertTrue(all("sourceId" not in f for f in changes["favorites"]))

    def test_schema1_import_ignores_hitomi(self):
        conn = sqlite3.connect(":memory:")
        init_user_db(conn, "tester")
        import_full_snapshot(conn, {
            "favorites": [
                {"id": "1", "title": "Old"},
                {"sourceId": "hitomi", "id": "2", "title": "Nope"},
            ],
        }, now_ms=1, schema=1)
        rows = list(conn.execute("SELECT sourceId, toonId FROM favorites"))
        self.assertEqual(rows, [("eleven", "1")])


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


class EntityIdTest(unittest.TestCase):
    def test_unprefixed_is_eleven(self):
        self.assertEqual(parse_entity_id("123"), ("eleven", "123"))
        self.assertEqual(parse_entity_id("hitomi:55"), ("hitomi", "55"))


if __name__ == "__main__":
    unittest.main()
