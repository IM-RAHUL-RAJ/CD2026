-- Schema for trading application (PostgreSQL)
-- Create tables referenced by MyBatis mappers and domain entities

-- Clients (joined by account)
CREATE TABLE client (
  client_id BIGSERIAL PRIMARY KEY,
  name TEXT NOT NULL
);

-- Accounts
CREATE TABLE account (
  account_id BIGSERIAL PRIMARY KEY,
  account_name TEXT NOT NULL,
  client_id BIGINT NOT NULL REFERENCES client(client_id),
  currency VARCHAR(8),
  cash_balance NUMERIC(19,2) NOT NULL DEFAULT 0,
  status VARCHAR(16) NOT NULL,
  version BIGINT NOT NULL DEFAULT 0,
  closed_at TIMESTAMPTZ
);

CREATE INDEX idx_account_client ON account(client_id);

-- Instruments
CREATE TABLE instrument (
  instrument_id BIGSERIAL PRIMARY KEY,
  symbol VARCHAR(128) NOT NULL UNIQUE,
  ticker VARCHAR(64),
  asset_class VARCHAR(64),
  quote_currency VARCHAR(8),
  status VARCHAR(32),
  delisted_at TIMESTAMPTZ
);

-- Holdings
CREATE TABLE holding (
  holding_id BIGSERIAL PRIMARY KEY,
  account_id BIGINT NOT NULL REFERENCES account(account_id) ON DELETE CASCADE,
  ticker VARCHAR(64) NOT NULL,
  quantity NUMERIC(30,8) NOT NULL,
  average_price NUMERIC(19,2) NOT NULL,
  as_of_date DATE NOT NULL
);

CREATE UNIQUE INDEX uq_holding_account_ticker ON holding(account_id, ticker);
CREATE INDEX idx_holding_account ON holding(account_id);

-- Orders
CREATE TABLE orders (
  order_id BIGSERIAL PRIMARY KEY,
  account_id BIGINT NOT NULL REFERENCES account(account_id) ON DELETE CASCADE,
  instrument_id BIGINT REFERENCES instrument(instrument_id),
  side VARCHAR(16) NOT NULL,
  quantity NUMERIC(30,8) NOT NULL,
  price NUMERIC(19,2) NOT NULL,
  status VARCHAR(32) NOT NULL,
  received_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  idempotency_key VARCHAR(255)
);

CREATE UNIQUE INDEX uq_orders_idempotency ON orders(idempotency_key);
CREATE INDEX idx_orders_account ON orders(account_id);
CREATE INDEX idx_orders_instrument ON orders(instrument_id);

-- Optional: ensure sequences are set to start at an appropriate minimum if needed
