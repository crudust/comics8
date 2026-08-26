from __future__ import annotations

import asyncio
import os
import sqlite3
import sys
import tempfile
import time
import pytest
import pytest_asyncio
from httpx import ASGITransport, AsyncClient
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker, create_async_engine

# Set test environment
os.environ["DATABASE_URL"] = "sqlite+aiosqlite:///:memory:"
os.environ["DATA_DIR"] = tempfile.mkdtemp()

sys.path.insert(0, os.path.dirname(__file__))

from app.config import get_settings
from app.database import Base, get_db, get_engine
from app.main import app
from app.models import User, Subscription, UserFavorite, UserHistory, UserReadEpisode, UserReaderSetting, UserTombstone
from app.sync import get_safe_user_id
from migrate_sqlite_to_pg import migrate_single_sqlite_db, save_user_data_to_pg


@pytest_asyncio.fixture
async def test_engine():
    engine = create_async_engine("sqlite+aiosqlite:///:memory:", echo=False)
    async with engine.begin() as conn:
        await conn.run_sync(Base.metadata.create_all)
    yield engine
    await engine.dispose()


@pytest_asyncio.fixture
async def test_session(test_engine):
    session_factory = async_sessionmaker(bind=test_engine, class_=AsyncSession, expire_on_commit=False)
    async with session_factory() as session:
        yield session


@pytest_asyncio.fixture
async def client(test_engine):
    session_factory = async_sessionmaker(bind=test_engine, class_=AsyncSession, expire_on_commit=False)

    async def override_get_db():
        async with session_factory() as session:
            try:
                yield session
                await session.commit()
            except Exception:
                await session.rollback()
                raise
            finally:
                await session.close()

    app.dependency_overrides[get_db] = override_get_db
    transport = ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url="http://test") as ac:
        yield ac
    app.dependency_overrides.clear()


# =============================================================================
# 1. Health and Version Tests
# =============================================================================

@pytest.mark.asyncio
async def test_health_check(client: AsyncClient):
    resp = await client.get("/health")
    assert resp.status_code == 200
    data = resp.json()
    assert data["status"] == "ok"
    assert data["service"] == "comics8-sync"
    assert data["syncSchema"] == 2
    assert "X-Comics8-Sync-Schema" in resp.headers

    head_resp = await client.head("/health")
    assert head_resp.status_code == 200


@pytest.mark.asyncio
async def test_version_check(client: AsyncClient):
    resp = await client.get("/version")
    assert resp.status_code == 200
    data = resp.json()
    assert "android" in data
    assert "desktop" in data
    assert data["android"]["versionCode"] >= 1


# =============================================================================
# 2. Pairing Workflow Tests
# =============================================================================

@pytest.mark.asyncio
async def test_pairing_workflow(client: AsyncClient):
    sync_key = "C8-TEST-PAIR-KEY1"

    # Request pairing code
    req_resp = await client.post(
        "/pair/request",
        headers={"X-Sync-Key": sync_key},
    )
    assert req_resp.status_code == 200
    req_data = req_resp.json()
    assert req_data["success"] is True
    code = req_data["code"]
    assert len(code) == 6

    # Confirm pairing code
    confirm_resp = await client.post("/pair/confirm", json={"code": code})
    assert confirm_resp.status_code == 200
    confirm_data = confirm_resp.json()
    assert confirm_data["success"] is True
    assert confirm_data["syncKey"] == sync_key

    # Re-confirming should fail (consumed)
    fail_resp = await client.post("/pair/confirm", json={"code": code})
    assert fail_resp.status_code == 400


# =============================================================================
# 3. Full Snapshot Sync Tests
# =============================================================================

@pytest.mark.asyncio
async def test_full_sync_import_and_export(client: AsyncClient):
    sync_key = "C8-SNAP-SHOT-KEY1"
    headers = {"X-Sync-Key": sync_key}

    payload = {
        "favorites": [
            {
                "sourceId": "eleven",
                "id": "101",
                "title": "Tower of God",
                "thumbUrl": "https://example.com/101.jpg",
                "href": "https://11toon.com/101",
                "genre": "Fantasy",
                "savedAt": 1000,
            },
            {
                "sourceId": "hitomi",
                "id": "202",
                "title": "Hitomi Manga",
                "savedAt": 1000,
            }
        ],
        "history": [
            {
                "sourceId": "eleven",
                "toonId": "101",
                "toonTitle": "Tower of God",
                "lastWrId": "ep-1",
                "lastEpisodeTitle": "Episode 1",
                "lastReadAt": 1000,
            }
        ],
        "readEpisodes": [
            {
                "sourceId": "eleven",
                "toonId": "101",
                "wrId": "ep-1",
                "readAt": 1000,
                "lastPage": 5,
            }
        ],
        "readerSettings": [
            {
                "sourceId": "eleven",
                "toonId": "101",
                "viewMode": "SINGLE",
                "readDirection": "RIGHT_TO_LEFT",
                "splitMode": "FIT",
                "updatedAt": 1000,
            }
        ],
    }

    # Import full snapshot
    post_resp = await client.post("/sync", headers=headers, json=payload)
    assert post_resp.status_code == 200
    post_data = post_resp.json()
    assert post_data["status"] == "success"
    assert post_data["favorites"] == 2
    assert post_data["history"] == 1

    # Export full snapshot
    get_resp = await client.get("/sync", headers=headers)
    assert get_resp.status_code == 200
    snap = get_resp.json()
    assert snap["version"] == 2
    assert len(snap["favorites"]) == 2
    assert len(snap["history"]) == 1
    assert len(snap["readEpisodes"]) == 1
    assert len(snap["readerSettings"]) == 1

    fav_ids = {(f["sourceId"], f["id"]) for f in snap["favorites"]}
    assert ("eleven", "101") in fav_ids
    assert ("hitomi", "202") in fav_ids


# =============================================================================
# 4. Incremental Delta Sync Tests
# =============================================================================

@pytest.mark.asyncio
async def test_delta_sync_lww_and_tombstones(client: AsyncClient):
    sync_key = "C8-DELTA-SYNC-KEY1"
    headers = {"X-Sync-Key": sync_key}

    # Step 1: Initial delta push
    delta1 = {
        "since": 0,
        "changes": {
            "favorites": [
                {"sourceId": "eleven", "id": "300", "title": "Delta Work A", "savedAt": 2000},
                {"sourceId": "eleven", "id": "301", "title": "Delta Work B", "savedAt": 2000},
            ],
            "history": [
                {"sourceId": "eleven", "toonId": "300", "toonTitle": "Delta Work A", "lastReadAt": 2000, "lastWrId": "1"},
            ],
        }
    }
    resp1 = await client.post("/sync/delta", headers=headers, json=delta1)
    assert resp1.status_code == 200
    server_time1 = resp1.json()["serverTime"]

    # Step 2: Client deletes Work A via tombstone, updates Work B
    del_ts = server_time1 + 1000
    delta2 = {
        "since": server_time1,
        "changes": {
            "tombstones": [
                {"entityType": "FAVORITE", "entityId": "eleven:300", "deletedAt": del_ts},
                {"entityType": "HISTORY", "entityId": "eleven:300", "deletedAt": del_ts},
            ],
            "favorites": [
                {"sourceId": "eleven", "id": "301", "title": "Delta Work B Renamed", "savedAt": del_ts},
            ]
        }
    }
    resp2 = await client.post("/sync/delta", headers=headers, json=delta2)
    assert resp2.status_code == 200

    # Step 3: Check snapshot - Work A should be deleted, Work B should be renamed
    snap_resp = await client.get("/sync", headers=headers)
    snap = snap_resp.json()
    fav_ids = [f["id"] for f in snap["favorites"]]
    assert "300" not in fav_ids
    assert "301" in fav_ids
    assert snap["favorites"][0]["title"] == "Delta Work B Renamed"
    assert len(snap["history"]) == 0

    # Step 4: Outbound delta query with since=server_time1
    delta_out = {
        "since": server_time1,
        "changes": {}
    }
    resp_out = await client.post("/sync/delta", headers=headers, json=delta_out)
    assert resp_out.status_code == 200
    changes = resp_out.json()["changes"]
    assert len(changes["tombstones"]) >= 2
    assert len(changes["favorites"]) == 1
    assert changes["favorites"][0]["id"] == "301"


# =============================================================================
# 5. Billing Verification & Status Tests
# =============================================================================

@pytest.mark.asyncio
async def test_billing_verification_and_pro_status(client: AsyncClient):
    sync_key = "C8-BILLING-TEST-KEY"
    headers = {"X-Sync-Key": sync_key}

    # Verify initial non-pro status
    status_resp1 = await client.get("/api/comics8/subscription/status", headers=headers)
    assert status_resp1.status_code == 200
    assert status_resp1.json()["isPro"] is False

    # Verify mock Google Play purchase token
    verify_resp = await client.post(
        "/api/comics8/subscription/verify",
        headers=headers,
        json={
            "purchaseToken": "mock_google_play_purchase_token_123",
            "productId": "comics8_pro_yearly",
            "basePlanId": "yearly",
        }
    )
    assert verify_resp.status_code == 200
    verify_data = verify_resp.json()
    assert verify_data["success"] is True
    assert verify_data["isPro"] is True
    assert verify_data["productId"] == "comics8_pro_yearly"

    # Status check should now reflect Pro
    status_resp2 = await client.get("/api/comics8/subscription/status", headers=headers)
    assert status_resp2.status_code == 200
    status_data = status_resp2.json()
    assert status_data["isPro"] is True
    assert status_data["subscription"]["productId"] == "comics8_pro_yearly"

    # Sync endpoint should also return isPro: true
    sync_resp = await client.get("/sync", headers=headers)
    assert sync_resp.json()["isPro"] is True


# =============================================================================
# 6. Proxy Endpoint Security Tests
# =============================================================================

@pytest.mark.asyncio
async def test_proxy_host_validation(client: AsyncClient):
    # Disallowed host should return 403
    forbidden_resp = await client.get("/proxy?url=http://malicious-site.example.com/image.jpg")
    assert forbidden_resp.status_code == 403

    # Invalid URL should return 400
    bad_resp = await client.get("/proxy?url=ftp://something")
    assert bad_resp.status_code == 400


# =============================================================================
# 7. Migration Logic Unit Tests
# =============================================================================

@pytest.mark.asyncio
async def test_migration_from_sqlite(test_session: AsyncSession):
    # Create mock SQLite database
    temp_dir = tempfile.mkdtemp()
    db_file = os.path.join(temp_dir, "user_test_mig_user.db")
    conn = sqlite3.connect(db_file)
    conn.execute("""
    CREATE TABLE favorites (
        sourceId TEXT, toonId TEXT, title TEXT, thumbUrl TEXT, href TEXT, genre TEXT, updatedAt TEXT, savedAt INTEGER, syncUpdatedAt INTEGER,
        PRIMARY KEY (sourceId, toonId)
    );
    """)
    conn.execute("""
    CREATE TABLE history (
        sourceId TEXT, toonId TEXT, toonTitle TEXT, toonThumbUrl TEXT, toonHref TEXT, lastWrId TEXT, lastEpisodeTitle TEXT, lastEpisodeHref TEXT,
        lastReadOrder INTEGER, totalEpisodes INTEGER, lastReadAt INTEGER, nextWrId TEXT, nextEpisodeTitle TEXT, nextEpisodeHref TEXT, hasNew INTEGER, syncUpdatedAt INTEGER,
        PRIMARY KEY (sourceId, toonId)
    );
    """)
    conn.execute("""
    CREATE TABLE read_episodes (
        sourceId TEXT, toonId TEXT, wrId TEXT, readAt INTEGER, lastPage INTEGER, syncUpdatedAt INTEGER,
        PRIMARY KEY (sourceId, toonId, wrId)
    );
    """)
    conn.execute("""
    CREATE TABLE reader_settings (
        sourceId TEXT, toonId TEXT, viewMode TEXT, readDirection TEXT, splitMode TEXT, updatedAt INTEGER, syncUpdatedAt INTEGER,
        PRIMARY KEY (sourceId, toonId)
    );
    """)
    conn.execute("""
    CREATE TABLE tombstones (
        entityType TEXT, entityId TEXT, deletedAt INTEGER, PRIMARY KEY (entityType, entityId)
    );
    """)

    conn.execute("INSERT INTO favorites VALUES ('eleven', '99', 'Migrated Fav', '', '', '', NULL, 50, 50)")
    conn.execute("INSERT INTO history VALUES ('eleven', '99', 'Migrated Hist', '', '', '1', 'ep1', '', 1, 10, 50, NULL, NULL, NULL, 0, 50)")
    conn.execute("INSERT INTO read_episodes VALUES ('eleven', '99', '1', 50, 2, 50)")
    conn.execute("INSERT INTO reader_settings VALUES ('eleven', '99', 'SCROLL', 'VERTICAL', 'FIT', 50, 50)")
    conn.execute("INSERT INTO tombstones VALUES ('FAVORITE', '100', 30)")
    conn.commit()
    conn.close()

    data = migrate_single_sqlite_db(db_file, "test_mig_user")
    assert len(data["favorites"]) == 1
    assert data["favorites"][0]["title"] == "Migrated Fav"
    assert len(data["tombstones"]) == 1
    assert data["tombstones"][0]["entity_id"] == "eleven:100"

    counts = await save_user_data_to_pg(test_session, "test_mig_user", data)
    assert counts["favorites"] == 1
    assert counts["history"] == 1
    assert counts["read_episodes"] == 1
    assert counts["reader_settings"] == 1
    assert counts["tombstones"] == 1

    # Verify saved in async session
    fav = await test_session.get(UserFavorite, ("test_mig_user", "eleven", "99"))
    assert fav is not None
    assert fav.title == "Migrated Fav"


# =============================================================================
# 8. File Download Endpoint Tests
# =============================================================================

@pytest.mark.asyncio
async def test_download_endpoints(client: AsyncClient):
    settings = get_settings()
    os.makedirs(settings.downloads_dir, exist_ok=True)
    sample_apk = os.path.join(settings.downloads_dir, "comics8-latest.apk")
    with open(sample_apk, "wb") as f:
        f.write(b"SAMPLE_APK_DATA")

    # GET /download/comics8-latest.apk
    get_resp = await client.get("/download/comics8-latest.apk")
    assert get_resp.status_code == 200
    assert get_resp.content == b"SAMPLE_APK_DATA"
    assert get_resp.headers["Content-Type"] == "application/vnd.android.package-archive"

    # HEAD /download/comics8-latest.apk
    head_resp = await client.head("/download/comics8-latest.apk")
    assert head_resp.status_code == 200
    assert head_resp.headers["Content-Length"] == str(len(b"SAMPLE_APK_DATA"))

    # Missing file 404
    missing_resp = await client.get("/download/non_existent.apk")
    assert missing_resp.status_code == 404


# =============================================================================
# 9. RTDN Pub/Sub Worker Payload Processing Test
# =============================================================================

@pytest.mark.asyncio
async def test_rtdn_pubsub_worker(client: AsyncClient, test_session: AsyncSession):
    from app.pubsub_worker import process_rtdn_payload
    from app.models import SubscriptionEvent

    # Test notification
    test_rtdn = {
        "version": "1.0",
        "packageName": "com.comics8.monitor",
        "eventTimeMillis": 1724650000000,
        "testNotification": {"version": "1.0"},
    }
    await process_rtdn_payload(test_rtdn, session=test_session)

    # Subscription Renewed RTDN
    renew_rtdn = {
        "version": "1.0",
        "packageName": "com.comics8.monitor",
        "eventTimeMillis": 1724650000000,
        "subscriptionNotification": {
            "version": "1.0",
            "notificationType": 2, # SUBSCRIPTION_RENEWED
            "purchaseToken": "mock_rtdn_purchase_token_456",
            "subscriptionId": "comics8_pro_monthly",
        },
    }
    await process_rtdn_payload(renew_rtdn, session=test_session)

    # Check event was logged
    stmt = select(SubscriptionEvent).where(SubscriptionEvent.purchase_token == "mock_rtdn_purchase_token_456")
    res = await test_session.execute(stmt)
    event = res.scalar_one_or_none()
    assert event is not None
    assert event.notification_type == 2
    assert "RENEWED" in event.event_type

