-- Test schema for H2 (PostgreSQL compatibility mode)

CREATE SCHEMA IF NOT EXISTS auth;

CREATE TABLE IF NOT EXISTS auth.users (
    user_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    uuid UUID NOT NULL UNIQUE,
    first_name VARCHAR(100),
    middle_name VARCHAR(100),
    last_name VARCHAR(100),
    username VARCHAR(100) NOT NULL UNIQUE,
    email VARCHAR(255) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    roles VARCHAR(500) NOT NULL DEFAULT 'CUSTOMER',
    created_on TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS account (
    account_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id BIGINT NOT NULL UNIQUE,
    currency CHAR(3) NOT NULL,
    cash_balance NUMERIC(18,2) NOT NULL DEFAULT 0,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    version BIGINT NOT NULL DEFAULT 0,
    closed_at TIMESTAMP WITH TIME ZONE,
    CONSTRAINT fk_account_user FOREIGN KEY (user_id) REFERENCES auth.users(user_id)
);

CREATE TABLE IF NOT EXISTS instrument (
    instrument_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    symbol VARCHAR(50) NOT NULL UNIQUE,
    ticker VARCHAR(255) NOT NULL UNIQUE,
    asset_class VARCHAR(30) NOT NULL,
    quote_currency CHAR(3) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    delisted_at TIMESTAMP WITH TIME ZONE
);

CREATE TABLE IF NOT EXISTS orders (
    order_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    account_id BIGINT NOT NULL,
    instrument_id BIGINT NOT NULL,
    side VARCHAR(4) NOT NULL,
    quantity NUMERIC(18,8) NOT NULL,
    price NUMERIC(18,8) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'NEW',
    received_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    idempotency_key VARCHAR(100) NOT NULL UNIQUE,
    CONSTRAINT fk_order_account FOREIGN KEY (account_id) REFERENCES account(account_id),
    CONSTRAINT fk_order_instrument FOREIGN KEY (instrument_id) REFERENCES instrument(instrument_id)
);

CREATE TABLE IF NOT EXISTS holding (
    holding_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    account_id BIGINT NOT NULL,
    ticker VARCHAR(255) NOT NULL,
    quantity NUMERIC(18,8) NOT NULL,
    average_price NUMERIC(18,8) NOT NULL,
    as_of_date DATE NOT NULL,
    CONSTRAINT fk_holding_account FOREIGN KEY (account_id) REFERENCES account(account_id),
    UNIQUE (account_id, ticker)
);