-- CLIENT
CREATE TABLE client (
    client_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name VARCHAR(150) NOT NULL,
    username VARCHAR(100) NOT NULL UNIQUE,
    password VARCHAR(255) NOT NULL
);


-- ACCOUNT
CREATE TABLE account (
    account_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    account_name VARCHAR(50) NOT NULL UNIQUE,
    client_id BIGINT NOT NULL,
    currency CHAR(3) NOT NULL,
    cash_balance NUMERIC(18,2) NOT NULL DEFAULT 0,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    version BIGINT NOT NULL DEFAULT 0,
    closed_at TIMESTAMP WITH TIME ZONE,

    CHECK (status IN ('ACTIVE', 'SUSPENDED', 'CLOSED')),
    CHECK (cash_balance >= 0),
    CHECK (
        (status = 'CLOSED' AND closed_at IS NOT NULL)
        OR
        (status <> 'CLOSED')
    ),

    CONSTRAINT fk_account_client
        FOREIGN KEY (client_id)
        REFERENCES client(client_id)
);


-- INSTRUMENT
CREATE TABLE instrument (
    instrument_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    symbol VARCHAR(50) NOT NULL UNIQUE,
    ticker VARCHAR(255) NOT NULL UNIQUE,
    asset_class VARCHAR(30) NOT NULL,
    quote_currency CHAR(3) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    delisted_at TIMESTAMP WITH TIME ZONE,

    CHECK (
        asset_class IN (
            'EQUITY',
            'ETF',
            'CURRENCY_PAIR',
            'CRYPTO_PAIR'
        )
    ),

    CHECK (
        status IN (
            'ACTIVE',
            'DELISTED'
        )
    ),

    CHECK (
        (status = 'DELISTED' AND delisted_at IS NOT NULL)
        OR
        (status = 'ACTIVE' AND delisted_at IS NULL)
    )
);


-- ORDERS
CREATE TABLE orders (
    order_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    account_id BIGINT NOT NULL,
    instrument_id BIGINT NOT NULL,
    side VARCHAR(4) NOT NULL,
    quantity NUMERIC(18,8) NOT NULL,
    price NUMERIC(18,8) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'NEW',
    received_at TIMESTAMP WITH TIME ZONE
        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    idempotency_key VARCHAR(100) NOT NULL UNIQUE,

    CONSTRAINT fk_order_account
        FOREIGN KEY (account_id)
        REFERENCES account(account_id),

    CONSTRAINT fk_order_instrument
        FOREIGN KEY (instrument_id)
        REFERENCES instrument(instrument_id),

    CHECK (side IN ('BUY', 'SELL')),
    CHECK (quantity > 0),
    CHECK (price > 0),

    CHECK (
        status IN (
            'NEW',
            'FILLED',
            'REJECTED',
            'CANCELLED'
        )
    )
);


-- HOLDING
CREATE TABLE holding (
    holding_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    account_id BIGINT NOT NULL,
    ticker VARCHAR(255) NOT NULL,
    quantity NUMERIC(18,8) NOT NULL,
    average_price NUMERIC(18,8) NOT NULL,
    as_of_date DATE NOT NULL,

    CONSTRAINT fk_holding_account
        FOREIGN KEY (account_id)
        REFERENCES account(account_id),

    CONSTRAINT fk_holding_ticker
        FOREIGN KEY (ticker)
        REFERENCES instrument(ticker),

    UNIQUE (account_id, ticker),

    CHECK (quantity >= 0),
    CHECK (average_price >= 0)
);


-- POSITIONS
CREATE TABLE position (
    order_id BIGINT NOT NULL UNIQUE,
    timestamp TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT fk_position_order
        FOREIGN KEY (order_id)
        REFERENCES orders(order_id)
);


-- ALERTS
CREATE TABLE alerts (
    alert_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    account_id BIGINT NOT NULL,
    ticker VARCHAR(255) NOT NULL,
    targeted_quantity NUMERIC(18,8) NOT NULL,
    alert_type VARCHAR(20) NOT NULL,
    message TEXT NOT NULL,

    CONSTRAINT fk_alerts_account
        FOREIGN KEY (account_id)
        REFERENCES account(account_id),

    CONSTRAINT fk_alerts_ticker
        FOREIGN KEY (ticker)
        REFERENCES instrument(ticker),

    CHECK (
        alert_type IN (
            'INFO',
            'AUTO_BUY',
            'AUTO_SELL'
        )
    ),

    CHECK (targeted_quantity > 0)
);


-- WATCH LIST
CREATE TABLE watchlist (
    watch_list_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    account_id BIGINT NOT NULL,

    CONSTRAINT fk_watchlist_account
        FOREIGN KEY (account_id)
        REFERENCES account(account_id)
);


-- WATCH LIST ITEMS
CREATE TABLE watch_list_items (
    watch_list_id BIGINT NOT NULL,
    ticker VARCHAR(255) NOT NULL,

    PRIMARY KEY (watch_list_id, ticker),

    CONSTRAINT fk_watch_list_items_watchlist
        FOREIGN KEY (watch_list_id)
        REFERENCES watchlist(watch_list_id),

    CONSTRAINT fk_watch_list_items_ticker
        FOREIGN KEY (ticker)
        REFERENCES instrument(ticker)
);


-- PERFORMANCE
CREATE TABLE performance (
    account_id BIGINT NOT NULL,
    date DATE NOT NULL,
    networth NUMERIC(18,2) NOT NULL,
    gain NUMERIC(18,2) NOT NULL,

    CONSTRAINT pk_performance
        PRIMARY KEY (account_id, date),

    CONSTRAINT fk_performance_account
        FOREIGN KEY (account_id)
        REFERENCES account(account_id)
);