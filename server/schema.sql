-- =============================================================================
-- Comics8 PostgreSQL 18 Database Schema
-- Optimized for high-throughput multi-user sync and Google Play In-App Billing
-- =============================================================================

-- Enable UUID extension if needed
CREATE EXTENSION IF NOT EXISTS "pgcrypto";

-- -----------------------------------------------------------------------------
-- 1. Users Table
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS users (
    user_id VARCHAR(64) PRIMARY KEY,
    sync_key TEXT UNIQUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_users_sync_key ON users(sync_key);

-- -----------------------------------------------------------------------------
-- 2. Subscriptions Table (Google Play In-App Purchases / Subscriptions)
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS subscriptions (
    id BIGSERIAL PRIMARY KEY,
    user_id VARCHAR(64) NOT NULL REFERENCES users(user_id) ON DELETE CASCADE,
    original_transaction_id VARCHAR(255) UNIQUE NOT NULL,
    purchase_token TEXT,
    product_id VARCHAR(128) NOT NULL,
    base_plan_id VARCHAR(128),
    status VARCHAR(64) NOT NULL DEFAULT 'ACTIVE', -- ACTIVE, CANCELED, EXPIRED, IN_GRACE_PERIOD, ON_HOLD, PAUSED
    is_active BOOLEAN NOT NULL DEFAULT FALSE,
    auto_renewing BOOLEAN NOT NULL DEFAULT TRUE,
    start_time TIMESTAMPTZ,
    expiry_time TIMESTAMPTZ NOT NULL,
    canceled_at TIMESTAMPTZ,
    payment_state VARCHAR(64),
    acknowledgement_state VARCHAR(64),
    latest_order_id VARCHAR(255),
    raw_data JSONB,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_subscriptions_user_active ON subscriptions(user_id, is_active);
CREATE INDEX IF NOT EXISTS idx_subscriptions_expiry ON subscriptions(expiry_time);
CREATE INDEX IF NOT EXISTS idx_subscriptions_token ON subscriptions(purchase_token);

-- -----------------------------------------------------------------------------
-- 3. Subscription Events Table (Google Play RTDN audit & history)
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS subscription_events (
    id BIGSERIAL PRIMARY KEY,
    user_id VARCHAR(64) REFERENCES users(user_id) ON DELETE SET NULL,
    original_transaction_id VARCHAR(255) NOT NULL,
    event_type VARCHAR(64) NOT NULL,
    notification_type INT,
    purchase_token TEXT,
    raw_payload JSONB,
    event_time TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_sub_events_tx ON subscription_events(original_transaction_id, event_time DESC);
CREATE INDEX IF NOT EXISTS idx_sub_events_user ON subscription_events(user_id);

-- -----------------------------------------------------------------------------
-- 4. User Favorites Table
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS user_favorites (
    user_id VARCHAR(64) NOT NULL REFERENCES users(user_id) ON DELETE CASCADE,
    source_id TEXT NOT NULL DEFAULT 'eleven',
    toon_id TEXT NOT NULL,
    title TEXT NOT NULL DEFAULT '',
    thumb_url TEXT NOT NULL DEFAULT '',
    href TEXT NOT NULL DEFAULT '',
    genre TEXT NOT NULL DEFAULT '',
    updated_at TEXT,
    saved_at BIGINT NOT NULL DEFAULT 0,
    sync_updated_at BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (user_id, source_id, toon_id)
);

CREATE INDEX IF NOT EXISTS idx_user_favorites_sync ON user_favorites(user_id, sync_updated_at);
CREATE INDEX IF NOT EXISTS idx_user_favorites_saved ON user_favorites(user_id, saved_at DESC);

-- -----------------------------------------------------------------------------
-- 5. User History Table
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS user_history (
    user_id VARCHAR(64) NOT NULL REFERENCES users(user_id) ON DELETE CASCADE,
    source_id TEXT NOT NULL DEFAULT 'eleven',
    toon_id TEXT NOT NULL,
    toon_title TEXT NOT NULL DEFAULT '',
    toon_thumb_url TEXT NOT NULL DEFAULT '',
    toon_href TEXT NOT NULL DEFAULT '',
    last_wr_id TEXT NOT NULL DEFAULT '',
    last_episode_title TEXT NOT NULL DEFAULT '',
    last_episode_href TEXT NOT NULL DEFAULT '',
    last_read_order INT NOT NULL DEFAULT 1,
    total_episodes INT NOT NULL DEFAULT 1,
    last_read_at BIGINT NOT NULL DEFAULT 0,
    next_wr_id TEXT,
    next_episode_title TEXT,
    next_episode_href TEXT,
    has_new BOOLEAN NOT NULL DEFAULT FALSE,
    sync_updated_at BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (user_id, source_id, toon_id)
);

CREATE INDEX IF NOT EXISTS idx_user_history_sync ON user_history(user_id, sync_updated_at);
CREATE INDEX IF NOT EXISTS idx_user_history_read_at ON user_history(user_id, last_read_at DESC);

-- -----------------------------------------------------------------------------
-- 6. User Read Episodes Table
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS user_read_episodes (
    user_id VARCHAR(64) NOT NULL REFERENCES users(user_id) ON DELETE CASCADE,
    source_id TEXT NOT NULL DEFAULT 'eleven',
    toon_id TEXT NOT NULL,
    wr_id TEXT NOT NULL,
    read_at BIGINT NOT NULL DEFAULT 0,
    last_page INT NOT NULL DEFAULT 0,
    sync_updated_at BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (user_id, source_id, toon_id, wr_id)
);

CREATE INDEX IF NOT EXISTS idx_user_read_episodes_sync ON user_read_episodes(user_id, sync_updated_at);

-- -----------------------------------------------------------------------------
-- 7. User Reader Settings Table
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS user_reader_settings (
    user_id VARCHAR(64) NOT NULL REFERENCES users(user_id) ON DELETE CASCADE,
    source_id TEXT NOT NULL DEFAULT 'eleven',
    toon_id TEXT NOT NULL,
    view_mode VARCHAR(32) NOT NULL DEFAULT 'SINGLE',
    read_direction VARCHAR(32) NOT NULL DEFAULT 'RIGHT_TO_LEFT',
    split_mode VARCHAR(32) NOT NULL DEFAULT 'FIT',
    updated_at BIGINT NOT NULL DEFAULT 0,
    sync_updated_at BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (user_id, source_id, toon_id)
);

CREATE INDEX IF NOT EXISTS idx_user_reader_settings_sync ON user_reader_settings(user_id, sync_updated_at);

-- -----------------------------------------------------------------------------
-- 8. User Tombstones Table (LWW soft-delete tracking)
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS user_tombstones (
    user_id VARCHAR(64) NOT NULL REFERENCES users(user_id) ON DELETE CASCADE,
    entity_type VARCHAR(32) NOT NULL,
    entity_id TEXT NOT NULL,
    deleted_at BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (user_id, entity_type, entity_id)
);

CREATE INDEX IF NOT EXISTS idx_user_tombstones_deleted ON user_tombstones(user_id, deleted_at DESC);

-- -----------------------------------------------------------------------------
-- 9. Pairing Codes Table (Device-to-device quick pairing)
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS pairing_codes (
    code VARCHAR(16) PRIMARY KEY,
    sync_key TEXT NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_pairing_codes_expiry ON pairing_codes(expires_at);

-- -----------------------------------------------------------------------------
-- 10. Global Toon Catalog Table (Crowdsourced episode counts, exact-or-lower-bound)
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS toon_catalog (
    source_id TEXT NOT NULL DEFAULT 'eleven',
    toon_id TEXT NOT NULL,
    total_episodes INT NOT NULL DEFAULT 0,
    updated_at BIGINT NOT NULL DEFAULT 0,
    -- true when total_episodes was read from the last page of the listing; only exact counts may
    -- correct a previously stored value downward.
    exact BOOLEAN NOT NULL DEFAULT FALSE,
    -- verified listing shape, so any client can recompute the total from a single page request
    last_page INT NOT NULL DEFAULT 0,
    last_page_item_count INT NOT NULL DEFAULT -1,
    PRIMARY KEY (source_id, toon_id)
);

CREATE INDEX IF NOT EXISTS idx_toon_catalog_toon_id ON toon_catalog(toon_id);
CREATE INDEX IF NOT EXISTS idx_toon_catalog_updated ON toon_catalog(updated_at DESC);

-- -----------------------------------------------------------------------------
-- 11. Toon Sources Table (Canonical source clusters and domain fingerprinting)
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS toon_sources (
    id TEXT PRIMARY KEY,
    display_name TEXT NOT NULL DEFAULT '',
    primary_host TEXT,
    known_hosts JSONB NOT NULL DEFAULT '[]'::jsonb,
    id_pattern TEXT,
    created_at BIGINT NOT NULL DEFAULT 0,
    updated_at BIGINT NOT NULL DEFAULT 0
);

CREATE INDEX IF NOT EXISTS idx_toon_sources_primary_host ON toon_sources(primary_host);

-- Seed well-known sources and mirrors.
-- created_at/updated_at are NOT NULL without a server default, so the seed must supply them;
-- otherwise a fresh schema.sql run fails halfway through and leaves the table empty.
INSERT INTO toon_sources (id, display_name, primary_host, known_hosts, id_pattern, created_at, updated_at)
VALUES
    ('eleven', '11toon', '11toon.com', '["11toon.com", "update.spotv24.com", "spotv24.com", "103.204.13.68", "fu01.11toon24.com", "update.11toon24.com", "11toon24.com", "11toon8.com"]'::jsonb, '^\d+$', 0, 0),
    ('hitomi', 'Hitomi', 'hitomi.la', '["hitomi.la"]'::jsonb, '^artist:.+$', 0, 0),
    ('rawkuma', 'Rawkuma', 'rawkuma.net', '["rawkuma.net"]'::jsonb, '^[a-zA-Z0-9%_-]+$', 0, 0)
ON CONFLICT (id) DO UPDATE SET
    display_name = EXCLUDED.display_name,
    primary_host = EXCLUDED.primary_host,
    known_hosts = EXCLUDED.known_hosts,
    id_pattern = EXCLUDED.id_pattern;
