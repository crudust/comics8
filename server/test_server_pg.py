from __future__ import annotations

import asyncio
import os
import sqlite3
import sys
import tempfile
import time
import pytest
import pytest_asyncio
import httpx
from httpx import ASGITransport, AsyncClient
from pydantic import ValidationError as PydanticValidationError
from sqlalchemy import event, select
from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker, create_async_engine

# Set test environment
os.environ["DATABASE_URL"] = "sqlite+aiosqlite:///:memory:"
os.environ["DATA_DIR"] = tempfile.mkdtemp()

sys.path.insert(0, os.path.dirname(__file__))

from app.config import Settings, get_settings
from app.database import Base, get_db, get_engine
from app.main import app
from app.models import User, Subscription, UserFavorite, UserHistory, UserReadEpisode, UserReaderSetting, UserTombstone
from app.sync import get_safe_user_id, import_snapshot_for_user
import app.proxy as proxy_module
import app.sync as sync_module
import app.billing as billing_module
from migrate_sqlite_to_pg import migrate_single_sqlite_db, save_user_data_to_pg


async def count_users(engine) -> int:
    session_factory = async_sessionmaker(bind=engine, class_=AsyncSession, expire_on_commit=False)
    async with session_factory() as session:
        return len((await session.execute(select(User))).scalars().all())


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
    created = await client.post(
        "/sync",
        headers={"X-Sync-Key": sync_key},
        json={"favorites": [{"sourceId": "eleven", "id": "pair-owner"}]},
    )
    assert created.status_code == 200

    # Request pairing code
    req_resp = await client.post(
        "/pair/request",
        headers={"X-Sync-Key": sync_key},
    )
    assert req_resp.status_code == 200
    req_data = req_resp.json()
    assert req_data["success"] is True
    assert req_data["expiresInSeconds"] == 300
    assert "expiresIn" not in req_data
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


@pytest.mark.asyncio
async def test_pairing_code_is_consumed_atomically(client: AsyncClient):
    sync_key = "C8-ATOMIC-PAIR"
    headers = {"X-Sync-Key": sync_key}
    created = await client.post(
        "/sync", headers=headers,
        json={"favorites": [{"sourceId": "eleven", "id": "atomic-pair"}]},
    )
    assert created.status_code == 200
    issued = await client.post("/pair/request", headers=headers)
    code = issued.json()["code"]

    first, second = await asyncio.gather(
        client.post("/pair/confirm", json={"code": code}),
        client.post("/pair/confirm", json={"code": code}),
    )
    assert sorted((first.status_code, second.status_code)) == [200, 400]


@pytest.mark.asyncio
async def test_pair_confirm_rejects_malformed_and_rate_limits_guesses(client: AsyncClient):
    settings = get_settings()
    original_limit = settings.PAIR_CONFIRM_MAX_ATTEMPTS
    original_window = settings.PAIR_CONFIRM_WINDOW_SECONDS
    settings.PAIR_CONFIRM_MAX_ATTEMPTS = 2
    settings.PAIR_CONFIRM_WINDOW_SECONDS = 60
    sync_module._pair_confirm_attempts.clear()
    try:
        malformed = await client.post("/pair/confirm", json={"code": "12345x"})
        first = await client.post("/pair/confirm", json={"code": "111111"})
        second = await client.post("/pair/confirm", json={"code": "222222"})
        blocked = await client.post("/pair/confirm", json={"code": "333333"})

        assert malformed.status_code == 400
        assert first.status_code == 400
        assert second.status_code == 400
        assert blocked.status_code == 429
        assert int(blocked.headers["Retry-After"]) > 0
    finally:
        sync_module._pair_confirm_attempts.clear()
        settings.PAIR_CONFIRM_MAX_ATTEMPTS = original_limit
        settings.PAIR_CONFIRM_WINDOW_SECONDS = original_window


@pytest.mark.asyncio
async def test_successful_pair_confirm_does_not_reset_guess_limit(client: AsyncClient):
    settings = get_settings()
    original_limit = settings.PAIR_CONFIRM_MAX_ATTEMPTS
    original_window = settings.PAIR_CONFIRM_WINDOW_SECONDS
    settings.PAIR_CONFIRM_MAX_ATTEMPTS = 2
    settings.PAIR_CONFIRM_WINDOW_SECONDS = 60
    sync_module._pair_confirm_attempts.clear()
    try:
        headers = {"X-Sync-Key": "C8-PAIR-RATE-SUCCESS"}
        created = await client.post(
            "/sync",
            headers=headers,
            json={"favorites": [{"sourceId": "eleven", "id": "pair-rate"}]},
        )
        assert created.status_code == 200
        issued = await client.post("/pair/request", headers=headers)
        code = issued.json()["code"]
        invalid_code = "111111" if code != "111111" else "222222"

        invalid = await client.post("/pair/confirm", json={"code": invalid_code})
        confirmed = await client.post("/pair/confirm", json={"code": code})
        blocked = await client.post("/pair/confirm", json={"code": "333333"})

        assert invalid.status_code == 400
        assert confirmed.status_code == 200
        assert blocked.status_code == 429
    finally:
        sync_module._pair_confirm_attempts.clear()
        settings.PAIR_CONFIRM_MAX_ATTEMPTS = original_limit
        settings.PAIR_CONFIRM_WINDOW_SECONDS = original_window


@pytest.mark.asyncio
async def test_sync_credentials_are_header_only(client: AsyncClient, test_engine):
    key = "C8-HEADER-ONLY"

    for name in ("key", "token"):
        response = await client.get("/sync", params={name: key})
        assert response.status_code == 401
    billing = await client.get("/api/comics8/subscription/status", params={"key": key})
    assert billing.status_code == 401
    assert await count_users(test_engine) == 0

    bearer = await client.get("/sync", headers={"Authorization": f"Bearer {key}"})
    custom_header = await client.get("/sync", headers={"X-Sync-Key": key})
    assert bearer.status_code == 200
    assert custom_header.status_code == 200

    created = await client.post(
        "/sync",
        headers={"X-Sync-Key": key},
        json={"favorites": [{"sourceId": "eleven", "id": "header-only"}]},
    )
    assert created.status_code == 200
    proxy_query = await client.get(
        "/proxy",
        params={"url": "https://tn.hitomi.la/image.jpg", "token": key},
    )
    assert proxy_query.status_code == 401


def test_database_url_has_no_credential_fallback(monkeypatch):
    monkeypatch.delenv("DATABASE_URL", raising=False)
    with pytest.raises(PydanticValidationError):
        Settings(_env_file=None)


@pytest.mark.asyncio
async def test_read_empty_and_pairing_requests_do_not_create_users(
    client: AsyncClient,
    test_engine,
):
    headers = {"X-Sync-Key": "C8-NO-DATA-ACCOUNT"}

    pull = await client.get("/sync", headers=headers)
    empty_snapshot = await client.post("/sync", headers=headers, json={})
    empty_delta = await client.post("/sync/delta", headers=headers, json={"changes": {}})
    billing_status = await client.get("/api/comics8/subscription/status", headers=headers)
    pairing = await client.post("/pair/request", headers=headers)

    assert pull.status_code == 200
    assert empty_snapshot.status_code == 200
    assert empty_delta.status_code == 200
    assert billing_status.status_code == 200
    assert pairing.status_code == 401
    assert await count_users(test_engine) == 0


@pytest.mark.asyncio
async def test_invalid_or_local_only_sync_does_not_create_user(
    client: AsyncClient,
    test_engine,
):
    headers = {"X-Sync-Key": "C8-INVALID-DATA"}

    malformed = await client.post(
        "/sync",
        headers={**headers, "Content-Type": "application/json"},
        content="{",
    )
    local_only = await client.post(
        "/sync",
        headers=headers,
        json={"favorites": [{"sourceId": "local", "id": "1"}]},
    )

    assert malformed.status_code == 400
    assert local_only.status_code == 200
    assert await count_users(test_engine) == 0


@pytest.mark.asyncio
async def test_invalid_tombstone_does_not_create_user(client: AsyncClient, test_engine):
    response = await client.post(
        "/sync/delta",
        headers={"X-Sync-Key": "C8-INVALID-TOMBSTONE"},
        json={"changes": {"tombstones": [{"entityId": "eleven:1"}]}},
    )

    assert response.status_code == 200
    assert await count_users(test_engine) == 0


@pytest.mark.asyncio
async def test_first_valid_sync_write_creates_user(client: AsyncClient, test_engine):
    headers = {"X-Sync-Key": "C8-FIRST-DATA-WRITE"}

    response = await client.post(
        "/sync",
        headers=headers,
        json={"favorites": [{"sourceId": "eleven", "id": "1", "title": "First"}]},
    )

    assert response.status_code == 200
    assert await count_users(test_engine) == 1


@pytest.mark.asyncio
async def test_sync_request_item_and_account_limits(client: AsyncClient, test_engine):
    settings = get_settings()
    original_request = settings.MAX_SYNC_REQUEST_BYTES
    original_items = settings.MAX_SYNC_ITEMS_PER_REQUEST
    original_account = settings.MAX_SYNC_ITEMS_PER_USER
    try:
        settings.MAX_SYNC_REQUEST_BYTES = 64
        oversized = await client.post(
            "/sync",
            headers={"X-Sync-Key": "C8-BODY-LIMIT"},
            json={"favorites": [{"sourceId": "eleven", "id": "1", "title": "x" * 100}]},
        )
        assert oversized.status_code == 413

        settings.MAX_SYNC_REQUEST_BYTES = original_request
        invalid_identifier = await client.post(
            "/sync",
            headers={"X-Sync-Key": "C8-ID-WIDTH"},
            json={"favorites": [{"sourceId": "x" * 33, "id": "1"}]},
        )
        assert invalid_identifier.status_code == 400

        settings.MAX_SYNC_ITEMS_PER_REQUEST = 1
        too_many = await client.post(
            "/sync/delta",
            headers={"X-Sync-Key": "C8-ITEM-LIMIT"},
            json={"changes": {"favorites": [
                {"sourceId": "eleven", "id": "1"},
                {"sourceId": "eleven", "id": "2"},
            ]}},
        )
        assert too_many.status_code == 413

        settings.MAX_SYNC_ITEMS_PER_REQUEST = original_items
        settings.MAX_SYNC_ITEMS_PER_USER = 1
        headers = {"X-Sync-Key": "C8-ACCOUNT-LIMIT"}
        first = await client.post(
            "/sync", headers=headers,
            json={"favorites": [{"sourceId": "eleven", "id": "1", "title": "first"}]},
        )
        update = await client.post(
            "/sync", headers=headers,
            json={"favorites": [{"sourceId": "eleven", "id": "1", "title": "updated"}]},
        )
        blocked = await client.post(
            "/sync", headers=headers,
            json={"favorites": [{"sourceId": "eleven", "id": "2"}]},
        )

        assert first.status_code == 200
        assert update.status_code == 200
        assert blocked.status_code == 413

        deleted = await client.post(
            "/sync/delta",
            headers=headers,
            json={"changes": {"tombstones": [{
                "entityType": "FAVORITE",
                "entityId": "eleven:1",
                "deletedAt": 1,
            }]}},
        )
        assert deleted.status_code == 200
        assert await count_users(test_engine) == 1
        async with async_sessionmaker(bind=test_engine, class_=AsyncSession)() as session:
            rows = (await session.execute(select(UserFavorite))).scalars().all()
            tombstones = (await session.execute(select(UserTombstone))).scalars().all()
            assert rows == []
            assert len(tombstones) == 1
    finally:
        settings.MAX_SYNC_REQUEST_BYTES = original_request
        settings.MAX_SYNC_ITEMS_PER_REQUEST = original_items
        settings.MAX_SYNC_ITEMS_PER_USER = original_account


@pytest.mark.asyncio
async def test_canonical_user_id_collision_cannot_replace_bound_key(
    client: AsyncClient,
    test_engine,
):
    original_key = "C8-COLLISION-KEY"
    colliding_key = f"{original_key}!!!"
    payload = {"favorites": [{"sourceId": "eleven", "id": "1"}]}

    created = await client.post("/sync", headers={"X-Sync-Key": original_key}, json=payload)
    rejected = await client.get("/sync", headers={"X-Sync-Key": colliding_key})

    assert created.status_code == 200
    assert rejected.status_code == 401
    session_factory = async_sessionmaker(bind=test_engine, class_=AsyncSession, expire_on_commit=False)
    async with session_factory() as session:
        user = await session.get(User, get_safe_user_id(original_key))
        assert user is not None
        assert user.sync_key == original_key


@pytest.mark.asyncio
async def test_hashed_legacy_user_binds_key_on_first_valid_write(
    client: AsyncClient,
    test_engine,
):
    legacy_key = "L" * 65
    legacy_user_id = get_safe_user_id(legacy_key)
    session_factory = async_sessionmaker(bind=test_engine, class_=AsyncSession, expire_on_commit=False)
    async with session_factory() as session:
        session.add(User(user_id=legacy_user_id, sync_key=None))
        await session.commit()

    pull = await client.get("/sync", headers={"X-Sync-Key": legacy_key})
    write = await client.post(
        "/sync",
        headers={"X-Sync-Key": legacy_key},
        json={"favorites": [{"sourceId": "eleven", "id": "1"}]},
    )

    assert pull.status_code == 200
    assert write.status_code == 200
    async with session_factory() as session:
        user = await session.get(User, legacy_user_id)
        assert user is not None
        assert user.sync_key == legacy_key


@pytest.mark.asyncio
async def test_snapshot_import_prefetches_favorites_in_one_query(test_session: AsyncSession):
    user_id = "bulk-prefetch-user"
    test_session.add(User(user_id=user_id, sync_key=user_id))
    await test_session.commit()

    statements: list[str] = []

    def record_statement(_conn, _cursor, statement, _parameters, _context, _executemany):
        if statement.lstrip().upper().startswith("SELECT"):
            statements.append(statement)

    engine = test_session.bind
    event.listen(engine.sync_engine, "before_cursor_execute", record_statement)
    try:
        await import_snapshot_for_user(
            test_session,
            user_id,
            {
                "favorites": [
                    {"sourceId": "eleven", "id": str(i), "title": f"Work {i}", "savedAt": i + 1}
                    for i in range(50)
                ]
            },
            now_ms=10_000,
        )
    finally:
        event.remove(engine.sync_engine, "before_cursor_execute", record_statement)

    favorite_selects = [
        sql for sql in statements
        if sql.lstrip().startswith("SELECT user_favorites.user_id")
    ]
    assert len(favorite_selects) == 1
    result = await test_session.execute(
        select(UserFavorite).where(UserFavorite.user_id == user_id)
    )
    assert len(result.scalars().all()) == 50


@pytest.mark.asyncio
async def test_proxy_reuses_application_client(client: AsyncClient, monkeypatch):
    upstream_calls = 0

    def upstream(_request: httpx.Request) -> httpx.Response:
        nonlocal upstream_calls
        upstream_calls += 1
        return httpx.Response(200, headers={"Content-Type": "image/jpeg"})

    headers = {"X-Sync-Key": "C8-PROXY-REUSE"}
    created = await client.post(
        "/sync", headers=headers,
        json={"favorites": [{"sourceId": "eleven", "id": "proxy-reuse"}]},
    )
    assert created.status_code == 200
    shared_client = httpx.AsyncClient(transport=httpx.MockTransport(upstream))
    monkeypatch.setattr(
        proxy_module.socket,
        "getaddrinfo",
        lambda *_args, **_kwargs: [(2, 1, 6, "", ("1.1.1.1", 443))],
    )
    app.state.proxy_client = shared_client
    app.state.proxy_semaphore = asyncio.Semaphore(16)
    try:
        for _ in range(2):
            response = await client.head(
                "/proxy",
                params={"url": "https://tn.hitomi.la/image.jpg"},
                headers=headers,
            )
            assert response.status_code == 200
        assert upstream_calls == 2
        assert not shared_client.is_closed
    finally:
        await shared_client.aclose()
        del app.state.proxy_client
        del app.state.proxy_semaphore


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
async def test_billing_verification_and_pro_status(client: AsyncClient, monkeypatch):
    sync_key = "C8-BILLING-TEST-KEY"
    headers = {"X-Sync-Key": sync_key}

    # Verify initial non-pro status
    status_resp1 = await client.get("/api/comics8/subscription/status", headers=headers)
    assert status_resp1.status_code == 200
    assert status_resp1.json()["isPro"] is False

    async def verified_purchase(purchase_token):
        return {
            "startTime": "2026-01-01T00:00:00+00:00",
            "subscriptionState": "SUBSCRIPTION_STATE_ACTIVE",
            "latestOrderId": "GPA.TEST-VERIFIED",
            "acknowledgementState": "ACKNOWLEDGEMENT_STATE_ACKNOWLEDGED",
            "lineItems": [{
                "productId": "comics8_pro_yearly",
                "expiryTime": "2099-01-01T00:00:00+00:00",
                "autoRenewingPlan": {"autoRenewEnabled": True},
                "offerDetails": {"basePlanId": "yearly"},
            }],
        }

    monkeypatch.setattr(billing_module, "verify_google_play_subscription_token", verified_purchase)

    # Persist an authoritatively verified Google Play result.
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


@pytest.mark.asyncio
async def test_billing_unavailable_does_not_create_user(client: AsyncClient, test_engine):
    response = await client.post(
        "/api/comics8/subscription/verify",
        headers={"X-Sync-Key": "C8-NO-BILLING-ACCOUNT"},
        json={"purchaseToken": "unverified-token"},
    )

    assert response.status_code == 503
    assert await count_users(test_engine) == 0


# =============================================================================
# 6. Proxy Endpoint Security Tests
# =============================================================================

@pytest.mark.asyncio
async def test_proxy_host_validation(client: AsyncClient):
    headers = {"X-Sync-Key": "C8-PROXY-HOST"}
    created = await client.post(
        "/sync", headers=headers,
        json={"favorites": [{"sourceId": "eleven", "id": "proxy-host"}]},
    )
    assert created.status_code == 200
    # Disallowed host should return 403
    forbidden_resp = await client.get(
        "/proxy?url=http://malicious-site.example.com/image.jpg", headers=headers
    )
    assert forbidden_resp.status_code == 403

    # Invalid URL should return 400
    bad_resp = await client.get("/proxy?url=ftp://something", headers=headers)
    assert bad_resp.status_code == 400

    forbidden_port = await client.get(
        "/proxy", params={"url": "http://hitomi.la:22/"}, headers=headers
    )
    assert forbidden_port.status_code == 403


@pytest.mark.asyncio
async def test_proxy_requires_materialized_sync_account(client: AsyncClient):
    missing = await client.get(
        "/proxy", params={"url": "https://tn.hitomi.la/image.jpg"}
    )
    unknown = await client.get(
        "/proxy",
        params={"url": "https://tn.hitomi.la/image.jpg"},
        headers={"X-Sync-Key": "C8-PROXY-NO-DATA"},
    )
    assert missing.status_code == 401
    assert unknown.status_code == 401


@pytest.mark.asyncio
async def test_proxy_pins_public_dns_and_preserves_https_identity(client: AsyncClient, monkeypatch):
    seen_request: httpx.Request | None = None

    class ImageStream(httpx.AsyncByteStream):
        async def __aiter__(self):
            yield b"image"

    def upstream(request: httpx.Request) -> httpx.Response:
        nonlocal seen_request
        seen_request = request
        return httpx.Response(200, headers={"Content-Type": "image/jpeg"}, stream=ImageStream())

    monkeypatch.setattr(
        proxy_module.socket,
        "getaddrinfo",
        lambda *_args, **_kwargs: [(2, 1, 6, "", ("1.1.1.1", 443))],
    )
    headers = {"X-Sync-Key": "C8-PROXY-PIN"}
    created = await client.post(
        "/sync", headers=headers,
        json={"favorites": [{"sourceId": "eleven", "id": "proxy-pin"}]},
    )
    assert created.status_code == 200
    shared_client = httpx.AsyncClient(transport=httpx.MockTransport(upstream))
    app.state.proxy_client = shared_client
    app.state.proxy_semaphore = asyncio.Semaphore(16)
    try:
        response = await client.get(
            "/proxy", params={"url": "https://tn.hitomi.la/image.jpg"}, headers=headers
        )
        assert response.status_code == 200
        assert response.content == b"image"
        assert seen_request is not None
        assert seen_request.url.host == "1.1.1.1"
        assert seen_request.headers["host"] == "tn.hitomi.la"
        assert seen_request.extensions["sni_hostname"] == "tn.hitomi.la"
    finally:
        await shared_client.aclose()
        del app.state.proxy_client
        del app.state.proxy_semaphore


@pytest.mark.asyncio
async def test_proxy_rejects_private_dns_and_oversized_response(client: AsyncClient, monkeypatch):
    upstream_calls = 0

    def upstream(_request: httpx.Request) -> httpx.Response:
        nonlocal upstream_calls
        upstream_calls += 1
        return httpx.Response(200, headers={"Content-Length": "11"}, content=b"hello world")

    headers = {"X-Sync-Key": "C8-PROXY-PRIVATE"}
    created = await client.post(
        "/sync", headers=headers,
        json={"favorites": [{"sourceId": "eleven", "id": "proxy-private"}]},
    )
    assert created.status_code == 200
    shared_client = httpx.AsyncClient(transport=httpx.MockTransport(upstream))
    app.state.proxy_client = shared_client
    app.state.proxy_semaphore = asyncio.Semaphore(16)
    try:
        monkeypatch.setattr(
            proxy_module.socket,
            "getaddrinfo",
            lambda *_args, **_kwargs: [(2, 1, 6, "", ("127.0.0.1", 443))],
        )
        rejected = await client.get(
            "/proxy", params={"url": "https://tn.hitomi.la/image.jpg"}, headers=headers
        )
        assert rejected.status_code == 403
        assert upstream_calls == 0

        monkeypatch.setattr(
            proxy_module.socket,
            "getaddrinfo",
            lambda *_args, **_kwargs: [(2, 1, 6, "", ("1.1.1.1", 443))],
        )
        settings = get_settings()
        original_limit = settings.MAX_PROXY_RESPONSE_BYTES
        settings.MAX_PROXY_RESPONSE_BYTES = 10
        try:
            head = await client.head(
                "/proxy", params={"url": "https://tn.hitomi.la/image.jpg"}, headers=headers
            )
            oversized = await client.get(
                "/proxy", params={"url": "https://tn.hitomi.la/image.jpg"}, headers=headers
            )
            assert head.status_code == 200
            assert oversized.status_code == 413
        finally:
            settings.MAX_PROXY_RESPONSE_BYTES = original_limit
    finally:
        await shared_client.aclose()
        del app.state.proxy_client
        del app.state.proxy_semaphore


@pytest.mark.asyncio
async def test_proxy_stops_chunked_response_at_byte_limit(monkeypatch):
    class ChunkedStream(httpx.AsyncByteStream):
        def __init__(self):
            self.closed = False

        async def __aiter__(self):
            yield b"123456"
            yield b"789012"

        async def aclose(self):
            self.closed = True

    stream = ChunkedStream()
    monkeypatch.setattr(
        proxy_module.socket,
        "getaddrinfo",
        lambda *_args, **_kwargs: [(2, 1, 6, "", ("1.1.1.1", 443))],
    )
    shared_client = httpx.AsyncClient(
        transport=httpx.MockTransport(
            lambda _request: httpx.Response(200, stream=stream)
        )
    )
    semaphore = asyncio.Semaphore(1)
    app.state.proxy_client = shared_client
    app.state.proxy_semaphore = semaphore
    settings = get_settings()
    original_limit = settings.MAX_PROXY_RESPONSE_BYTES
    settings.MAX_PROXY_RESPONSE_BYTES = 10
    request = proxy_module.Request({
        "type": "http",
        "method": "GET",
        "path": "/proxy",
        "query_string": b"url=https%3A%2F%2Ftn.hitomi.la%2Fimage.jpg",
        "headers": [],
        "app": app,
    })
    try:
        response = await proxy_module.handle_proxy_request(request)
        received = []
        with pytest.raises(RuntimeError, match="configured limit"):
            async for chunk in response.body_iterator:
                received.append(chunk)
        assert received == []
        assert stream.closed is True
        await asyncio.wait_for(semaphore.acquire(), timeout=0.1)
        semaphore.release()
    finally:
        settings.MAX_PROXY_RESPONSE_BYTES = original_limit
        await shared_client.aclose()
        del app.state.proxy_client
        del app.state.proxy_semaphore


@pytest.mark.asyncio
async def test_proxy_cancellation_releases_capacity(monkeypatch):
    entered = asyncio.Event()
    blocked = asyncio.Event()

    async def stalled_resolve(_url: str):
        entered.set()
        await blocked.wait()

    monkeypatch.setattr(proxy_module, "resolve_proxy_target", stalled_resolve)
    shared_client = httpx.AsyncClient(transport=httpx.MockTransport(lambda _request: httpx.Response(200)))
    semaphore = asyncio.Semaphore(1)
    app.state.proxy_client = shared_client
    app.state.proxy_semaphore = semaphore
    request = proxy_module.Request({
        "type": "http",
        "method": "GET",
        "path": "/proxy",
        "query_string": b"url=https%3A%2F%2Ftn.hitomi.la%2Fimage.jpg",
        "headers": [],
        "app": app,
    })
    task = asyncio.create_task(proxy_module.handle_proxy_request(request))
    try:
        await asyncio.wait_for(entered.wait(), timeout=0.1)
        task.cancel()
        with pytest.raises(asyncio.CancelledError):
            await task
        await asyncio.wait_for(semaphore.acquire(), timeout=0.1)
        semaphore.release()
    finally:
        task.cancel()
        await shared_client.aclose()
        del app.state.proxy_client
        del app.state.proxy_semaphore


@pytest.mark.asyncio
async def test_proxy_revalidates_redirect_dns(client: AsyncClient, monkeypatch):
    headers = {"X-Sync-Key": "C8-PROXY-REDIRECT"}
    created = await client.post(
        "/sync", headers=headers,
        json={"favorites": [{"sourceId": "eleven", "id": "proxy-redirect"}]},
    )
    assert created.status_code == 200

    dns_calls = 0
    upstream_calls = 0

    def resolve(*_args, **_kwargs):
        nonlocal dns_calls
        dns_calls += 1
        address = "1.1.1.1" if dns_calls == 1 else "127.0.0.1"
        return [(2, 1, 6, "", (address, 443))]

    def upstream(_request: httpx.Request) -> httpx.Response:
        nonlocal upstream_calls
        upstream_calls += 1
        return httpx.Response(302, headers={"Location": "https://hitomi.la/private"})

    monkeypatch.setattr(proxy_module.socket, "getaddrinfo", resolve)
    shared_client = httpx.AsyncClient(transport=httpx.MockTransport(upstream))
    app.state.proxy_client = shared_client
    app.state.proxy_semaphore = asyncio.Semaphore(16)
    try:
        response = await client.get(
            "/proxy", params={"url": "https://tn.hitomi.la/image.jpg"}, headers=headers
        )
        assert response.status_code == 403
        assert dns_calls == 2
        assert upstream_calls == 1
    finally:
        await shared_client.aclose()
        del app.state.proxy_client
        del app.state.proxy_semaphore


@pytest.mark.asyncio
async def test_proxy_returns_503_when_concurrency_is_full(client: AsyncClient):
    headers = {"X-Sync-Key": "C8-PROXY-CAPACITY"}
    created = await client.post(
        "/sync", headers=headers,
        json={"favorites": [{"sourceId": "eleven", "id": "proxy-capacity"}]},
    )
    assert created.status_code == 200

    shared_client = httpx.AsyncClient(transport=httpx.MockTransport(lambda _request: httpx.Response(200)))
    app.state.proxy_client = shared_client
    app.state.proxy_semaphore = asyncio.Semaphore(0)
    try:
        response = await client.get(
            "/proxy", params={"url": "https://tn.hitomi.la/image.jpg"}, headers=headers
        )
        assert response.status_code == 503
    finally:
        await shared_client.aclose()
        del app.state.proxy_client
        del app.state.proxy_semaphore


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
