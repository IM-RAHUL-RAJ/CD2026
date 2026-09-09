-- COVERAGE:
--  Accounts in all three states, ACTIVE, SUSPENDED and CLOSED
--  An account whose cash cannot afford a realistic order
--  An account having a holding and that order id is recorded in position
--  Several instruments, at least one not an equity
--  An instrument that no longer trades, still referenced by an older order and by a holding
--  Orders in all four lifecycle states
--  Holdings that reconcile against the filled orders that produced them


-- CLIENT

INSERT INTO client (client_id, name, username, password)
OVERRIDING SYSTEM VALUE
VALUES
    (1001, 'Arun Kumar', 'arun.kumar',
        '$2b$12$LQv3c1yqBWVHxkd0LHAkCOyF8Z7Z0h6Q9K8KJ5X1Y2Z3A4B5C6D7E'),

    (1002, 'Priya Sharma', 'priya.sharma',
        '$2b$12$NQv3c1yqBWVHxkd0LHAkCOyF8Z7Z0h6Q9K8KJ5X1Y2Z3A4B5C6D7E'),

    (1003, 'Rahul Menon', 'rahul.menon',
        '$2b$12$PQv3c1yqBWVHxkd0LHAkCOyF8Z7Z0h6Q9K8KJ5X1Y2Z3A4B5C6D7E'),

    (1004, 'Monica Rao', 'sneha.rao',
        '$2b$12$RQv3c1yqBWVHxkd0LHAkCOyF8Z7Z0h6Q9K8KJ5X1Y2Z3A4B5C6D7E');


-- ACCOUNT

INSERT INTO account (
    account_id,
    account_name,
    client_id,
    currency,
    cash_balance,
    status,
    version,
    closed_at
)
OVERRIDING SYSTEM VALUE
VALUES
    -- Active account with enough cash
    (100001, 'ACC-ARUN-001', 1001, 'USD', 25000.00, 'ACTIVE', 0, NULL),

    -- Suspended account
    (100002, 'ACC-PRIYA-001', 1002, 'USD', 15000.00, 'SUSPENDED', 0, NULL),

    -- Closed account
    (100003, 'ACC-RAHUL-001', 1003, 'USD', 8000.00, 'CLOSED', 0,
        '2026-08-20 15:30:00+00'),

    -- Active account that cannot afford a realistic order
    (100004, 'ACC-SNEHA-001', 1004, 'USD', 100.00, 'ACTIVE', 0, NULL);


-- INSTRUMENT

INSERT INTO instrument (
    instrument_id,
    symbol,
    ticker,
    asset_class,
    quote_currency,
    status,
    delisted_at
)
OVERRIDING SYSTEM VALUE
VALUES
    -- Active equity
    (1, 'AAPL', 'AAPL', 'EQUITY', 'USD', 'ACTIVE', NULL),

    -- Active equity
    (2, 'MSFT', 'MSFT', 'EQUITY', 'USD', 'ACTIVE', NULL),

    -- Active ETF
    (3, 'SPY', 'SPY', 'ETF', 'USD', 'ACTIVE', NULL),

    -- Active currency pair
    (4, 'EUR/USD', 'EURUSD', 'CURRENCY_PAIR', 'USD', 'ACTIVE', NULL),

    -- Active crypto pair
    (5, 'BTC/USD', 'BTCUSD', 'CRYPTO_PAIR', 'USD', 'ACTIVE', NULL),

    -- Delisted equity
    (6, 'TWTR', 'TWTR', 'EQUITY', 'USD', 'DELISTED',
        '2022-11-08 16:00:00+00');


-- ORDERS

INSERT INTO orders (
    order_id,
    account_id,
    instrument_id,
    side,
    quantity,
    price,
    status,
    received_at,
    idempotency_key
)
OVERRIDING SYSTEM VALUE
VALUES
    -- FILLED order that produced Arun's AAPL holding
    -- 50 AAPL @ $200
    (1, 100001, 1, 'BUY', 50, 200.00, 'FILLED',
        '2026-08-25 10:00:00+00',
        'IDEMP-001'),

    -- FILLED order that produced Arun's MSFT holding
    -- 20 MSFT @ $400
    (2, 100001, 2, 'BUY', 20, 400.00, 'FILLED',
        '2026-08-26 11:00:00+00',
        'IDEMP-002'),

    -- FILLED historical order for a delisted instrument
    (3, 100003, 6, 'BUY', 100, 40.00, 'FILLED',
        '2022-11-01 09:30:00+00',
        'IDEMP-003'),

    -- REJECTED order
    (4, 100002, 1, 'BUY', 100, 200.00, 'REJECTED',
        '2026-08-27 12:00:00+00',
        'IDEMP-004'),

    -- CANCELLED order
    (5, 100001, 3, 'BUY', 10, 600.00, 'CANCELLED',
        '2026-08-28 14:00:00+00',
        'IDEMP-005'),

    -- NEW order
    (6, 100001, 4, 'BUY', 1000, 1.10, 'NEW',
        '2026-09-01 10:00:00+00',
        'IDEMP-006');


-- HOLDING

INSERT INTO holding (
    holding_id,
    account_id,
    ticker,
    quantity,
    average_price,
    as_of_date
)
OVERRIDING SYSTEM VALUE
VALUES
    -- Reconciles with order 1:
    -- 50 AAPL @ $200
    (1, 100001, 'AAPL', 50, 200.00, '2026-08-25'),

    -- Reconciles with order 2:
    -- 20 MSFT @ $400
    (2, 100001, 'MSFT', 20, 400.00, '2026-08-26'),

    -- Reconciles with order 3:
    -- 100 TWTR @ $40
    (3, 100003, 'TWTR', 100, 40.00, '2022-11-01');


-- POSITION

INSERT INTO position (
    order_id,
    timestamp
)
VALUES
    -- Position created from filled AAPL order
    (1, '2026-08-25 10:05:00'),

    -- Position created from filled MSFT order
    (2, '2026-08-26 11:05:00'),

    -- Position created from historical TWTR order
    (3, '2022-11-01 09:35:00');


-- ALERTS

INSERT INTO alerts (
    alert_id,
    account_id,
    ticker,
    targeted_quantity,
    alert_type,
    message
)
OVERRIDING SYSTEM VALUE
VALUES

    (1, 100001, 'AAPL', 500,
        'INFO',
        'AAPL has reached the configured target quantity.'),

    (2, 100001, 'MSFT', 200,
        'AUTO_BUY',
        'Automatic buy condition triggered for MSFT.'),

    (3, 100003, 'TWTR', 100,
        'AUTO_SELL',
        'Automatic sell condition triggered for TWTR.');


-- WATCHLIST

INSERT INTO watchlist (
    watch_list_id,
    account_id
)
OVERRIDING SYSTEM VALUE
VALUES
    (1, 100001),

    (2, 100002),

    (3, 100004);


-- WATCH LIST ITEMS

INSERT INTO watch_list_items (
    watch_list_id,
    ticker
)
VALUES
    -- Arun watches AAPL, SPY and BTC/USD
    (1, 'AAPL'),
    (1, 'SPY'),
    (1, 'BTCUSD'),

    -- Priya watches MSFT and EUR/USD
    (2, 'MSFT'),
    (2, 'EURUSD'),

    -- Sneha watches AAPL and BTC/USD
    (3, 'AAPL'),
    (3, 'BTCUSD');


-- PERFORMANCE

INSERT INTO performance (
    account_id,
    date,
    networth,
    gain
)
VALUES

    (100001, '2026-08-25', 25000.00, 0.00),
    (100001, '2026-08-26', 25500.00, 5.00),
    (100001, '2026-08-27', 25800.00, 30.00),

    (100002, '2026-08-25', 15000.00, 18.00),
    (100002, '2026-08-26', 14900.00, -10.00),


    (100003, '2026-08-20', 8000.00, 0.00),
    (100003, '2026-08-21', 7900.00, -28.00),

    (100004, '2026-09-01', 100.00, 7.00);