-- ============================================================================
-- Sprint 8 database bootstrap
-- Database: trading_system_db
-- Two schemas: trading (owned by the Trade REST API) and auth (auth service).
-- ============================================================================

CREATE SCHEMA IF NOT EXISTS trading;
CREATE SCHEMA IF NOT EXISTS auth;

-- Make unqualified table names resolve to the trading schema for every
-- connection opened with the postgres role inside this database. This lets the
-- unchanged executor and ETL (which query "account", "orders", "holding",
-- "instrument" unqualified) keep working against the trading schema.
ALTER ROLE postgres IN DATABASE trading_system_db SET search_path TO trading, public;

-- ============================================================================
-- Auth schema
-- ============================================================================

-- Users are created by the Sprint 8 auth service. user_id is the auto-increment
-- primary key; uuid is the stable identifier carried in the JWT "sub" claim.
CREATE TABLE auth.users (
    user_id       BIGSERIAL PRIMARY KEY,
    uuid          UUID NOT NULL DEFAULT gen_random_uuid(),
    first_name    TEXT NOT NULL,
    middle_name   TEXT,
    last_name     TEXT NOT NULL,
    username      VARCHAR(64) NOT NULL UNIQUE,
    email         VARCHAR(255) NOT NULL UNIQUE,
    password_hash TEXT NOT NULL,
    roles         TEXT[] NOT NULL DEFAULT ARRAY['CUSTOMER'],
    created_on    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX uq_users_uuid ON auth.users (uuid);

-- Refresh tokens are stored hashed (SHA-256 of the token value) so that read
-- access to the database is not session takeover. Rotated on every refresh and
-- revoked when a presented token has already been exchanged (replay).
CREATE TABLE auth.refresh_token (
    refresh_token_id BIGSERIAL PRIMARY KEY,
    user_id          BIGINT NOT NULL REFERENCES auth.users (user_id) ON DELETE CASCADE,
    token_hash       TEXT NOT NULL UNIQUE,
    family_id        UUID NOT NULL DEFAULT gen_random_uuid(),
    expires_at       TIMESTAMPTZ NOT NULL,
    revoked_on       TIMESTAMPTZ,
    created_on       TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_refresh_token_user ON auth.refresh_token (user_id);
CREATE INDEX idx_refresh_token_family ON auth.refresh_token (family_id);

-- ============================================================================
-- Trading schema
-- ============================================================================

-- Accounts are owned by a user. account_id is auto-incremented; a new account
-- starts with cash_balance 100000, currency USD, version 1. version increments
-- on every update (optimistic locking by the executor).
CREATE TABLE trading.account (
    account_id   BIGSERIAL PRIMARY KEY,
    user_id      BIGINT NOT NULL UNIQUE REFERENCES auth.users (user_id) ON DELETE CASCADE,
    currency     VARCHAR(8) NOT NULL DEFAULT 'USD',
    cash_balance NUMERIC(19,2) NOT NULL DEFAULT 100000.00,
    status       VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    version      BIGINT NOT NULL DEFAULT 1,
    closed_at    TIMESTAMPTZ
);

CREATE INDEX idx_account_user ON trading.account (user_id);

CREATE TABLE trading.instrument (
    instrument_id  BIGSERIAL PRIMARY KEY,
    symbol         VARCHAR(128) NOT NULL UNIQUE,
    ticker         VARCHAR(64),
    asset_class    VARCHAR(64),
    quote_currency VARCHAR(8),
    status         VARCHAR(32),
    delisted_at    TIMESTAMPTZ
);

CREATE TABLE trading.holding (
    holding_id    BIGSERIAL PRIMARY KEY,
    account_id    BIGINT NOT NULL REFERENCES trading.account (account_id) ON DELETE CASCADE,
    ticker        VARCHAR(64) NOT NULL,
    quantity      NUMERIC(30,8) NOT NULL,
    average_price NUMERIC(19,2) NOT NULL,
    as_of_date    DATE NOT NULL
);

CREATE UNIQUE INDEX uq_holding_account_ticker ON trading.holding (account_id, ticker);
CREATE INDEX idx_holding_account ON trading.holding (account_id);

CREATE TABLE trading.orders (
    order_id        BIGSERIAL PRIMARY KEY,
    account_id      BIGINT NOT NULL REFERENCES trading.account (account_id) ON DELETE CASCADE,
    ticker          VARCHAR(64),
    side            VARCHAR(16) NOT NULL,
    quantity        NUMERIC(30,8) NOT NULL,
    price           NUMERIC(19,2) NOT NULL,
    order_type      VARCHAR(16) NOT NULL DEFAULT 'LIMIT',
    status          VARCHAR(32) NOT NULL,
    received_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    idempotency_key VARCHAR(255),
    executed_price  NUMERIC(18,8),
    executed_on     TIMESTAMPTZ,
    rejection_reason VARCHAR(255)
);

CREATE UNIQUE INDEX uq_orders_idempotency ON trading.orders (idempotency_key);
CREATE INDEX idx_orders_account ON trading.orders (account_id);