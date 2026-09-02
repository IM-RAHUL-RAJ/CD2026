-- CLIENT
CREATE TABLE client (
    client_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name VARCHAR(150) NOT NULL,
    username VARCHAR(100) NOT NULL,
    password VARCHAR(255) NOT NULL
);

-- INSTRUMENT
CREATE TABLE instrument (
    instrument_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    ticket VARCHAR(20) NOT NULL UNIQUE,
    name VARCHAR(150) NOT NULL
);

-- ACCOUNT
CREATE TABLE account (
    account_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    client_id BIGINT NOT NULL,
    account_type VARCHAR(20) NOT NULL,
    wallet_amount NUMERIC(18,2) NOT NULL,
    risk_profile VARCHAR(20) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',

    CONSTRAINT fk_account_client
        FOREIGN KEY (client_id)
        REFERENCES client(client_id),

    CHECK (account_type IN ('TRADING', 'DEMAT')),
    CHECK (wallet_amount > 0),
    CHECK (
        risk_profile IN (
            'CONSERVATIVE',
            'MODERATE',
            'AGGRESSIVE'
        )
    ),
    CHECK (
        status IN (
            'ACTIVE',
            'SUSPENDED',
            'CLOSED'
        )
    )
);

-- HOLDINGS
CREATE TABLE holdings (
    account_id BIGINT NOT NULL,
    instrument_id BIGINT NOT NULL,
    quantity INTEGER NOT NULL,
    investment_amount NUMERIC(18,2) NOT NULL,
    average NUMERIC(18,6) NOT NULL,
    as_of_date DATE NOT NULL,

    CONSTRAINT pk_holdings
        PRIMARY KEY (account_id, instrument_id),

    CONSTRAINT fk_holdings_account
        FOREIGN KEY (account_id)
        REFERENCES account(account_id),

    CONSTRAINT fk_holdings_instrument
        FOREIGN KEY (instrument_id)
        REFERENCES instrument(instrument_id),

    CHECK (quantity > 0),
    CHECK (investment_amount > 0),
    CHECK (average > 0)
);

-- ALERTS
CREATE TABLE alerts (
    alert_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    account_id BIGINT NOT NULL,
    instrument_id BIGINT NOT NULL,
    message TEXT NOT NULL,

    CONSTRAINT fk_alerts_account
        FOREIGN KEY (account_id)
        REFERENCES account(account_id),

    CONSTRAINT fk_alerts_instrument
        FOREIGN KEY (instrument_id)
        REFERENCES instrument(instrument_id)
);

-- ORDERS
CREATE TABLE orders (
    order_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    instrument_id BIGINT NOT NULL,
    account_id BIGINT NOT NULL,
    quantity INTEGER NOT NULL,
    price NUMERIC(18,6) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'NEW',
    timestamp TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    idempotency_key VARCHAR(255) NOT NULL UNIQUE,

    CONSTRAINT fk_orders_instrument
        FOREIGN KEY (instrument_id)
        REFERENCES instrument(instrument_id),

    CONSTRAINT fk_orders_account
        FOREIGN KEY (account_id)
        REFERENCES account(account_id),

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

-- POSITIONS
CREATE TABLE position (
    order_id BIGINT NOT NULL UNIQUE,
    timestamp TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT fk_position_order
        FOREIGN KEY (order_id)
        REFERENCES orders(order_id)
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
    instrument_id BIGINT NOT NULL,

    CONSTRAINT fk_watch_list_items_watchlist
        FOREIGN KEY (watch_list_id)
        REFERENCES watchlist(watch_list_id),

    CONSTRAINT fk_watch_list_items_instrument
        FOREIGN KEY (instrument_id)
        REFERENCES instrument(instrument_id)
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


-- INDEXES
CREATE INDEX idx_performance_date
ON performance(date);

CREATE INDEX idx_holdings_as_of_date
ON holdings(as_of_date);


-- TRIGGER FUNCTION
CREATE OR REPLACE FUNCTION validate_order_status_transition()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF OLD.status IN ('FILLED', 'REJECTED', 'CANCELLED')
       AND NEW.status <> OLD.status THEN

        RAISE EXCEPTION
            'Order % is already in terminal status % and cannot be changed',
            OLD.order_id,
            OLD.status;

    END IF;

    RETURN NEW;
END;
$$;


-- TRIGGER
CREATE TRIGGER trg_validate_order_status_transition
BEFORE UPDATE OF status
ON orders
FOR EACH ROW
EXECUTE FUNCTION validate_order_status_transition();