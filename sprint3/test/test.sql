-- CLIENT

-- CLIENT: UNIQUE constraint on username
INSERT INTO client (
    name,
    username,
    password
)
VALUES (
    'Duplicate User',
    'arun.kumar',
    'hashed_password'
);

-- CLIENT: NOT NULL constraint on name
INSERT INTO client (
    name,
    username,
    password
)
VALUES (
    NULL,
    'test.null.name',
    'hashed_password'
);


-- ACCOUNT

-- ACCOUNT: FOREIGN KEY constraint on client_id
INSERT INTO account (
    account_name,
    client_id,
    currency,
    cash_balance,
    status
)
VALUES (
    'TEST-NO-CLIENT',
    999999,
    'USD',
    10000.00,
    'ACTIVE'
);

-- ACCOUNT: CHECK constraint on status
INSERT INTO account (
    account_name,
    client_id,
    currency,
    cash_balance,
    status
)
VALUES (
    'TEST-INVALID-STATUS',
    1001,
    'USD',
    10000.00,
    'BLOCKED'
);

-- ACCOUNT: CHECK constraint on cash_balance >= 0
INSERT INTO account (
    account_name,
    client_id,
    currency,
    cash_balance,
    status
)
VALUES (
    'TEST-NEGATIVE-CASH',
    1001,
    'USD',
    -100.00,
    'ACTIVE'
);

-- ACCOUNT: CHECK constraint requiring closed_at for CLOSED accounts
INSERT INTO account (
    account_name,
    client_id,
    currency,
    cash_balance,
    status,
    closed_at
)
VALUES (
    'TEST-CLOSED-NO-DATE',
    1001,
    'USD',
    10000.00,
    'CLOSED',
    NULL
);

-- ACCOUNT: CLOSED account cannot be reopened
-- Step 1: Create a CLOSED account
INSERT INTO account (
    account_name,
    client_id,
    currency,
    cash_balance,
    status,
    closed_at
)
VALUES (
    'TEST-CLOSED-ACCOUNT',
    1001,
    'USD',
    10000.00,
    'CLOSED',
    CURRENT_TIMESTAMP
);

-- Step 2: Attempt to change CLOSED -> ACTIVE
UPDATE account
SET status = 'ACTIVE'
WHERE account_name = 'TEST-CLOSED-ACCOUNT';


-- INSTRUMENT

-- INSTRUMENT: UNIQUE constraint on symbol
INSERT INTO instrument (
    symbol,
    ticker,
    asset_class,
    quote_currency,
    status
)
VALUES (
    'AAPL',
    'TEST-AAPL',
    'EQUITY',
    'USD',
    'ACTIVE'
);

-- INSTRUMENT: CHECK constraint on asset_class
INSERT INTO instrument (
    symbol,
    ticker,
    asset_class,
    quote_currency,
    status
)
VALUES (
    'TEST-ASSET',
    'TEST-ASSET',
    'STOCK',
    'USD',
    'ACTIVE'
);

-- INSTRUMENT: CHECK constraint on status/delisted_at
-- A DELISTED instrument must have delisted_at
INSERT INTO instrument (
    symbol,
    ticker,
    asset_class,
    quote_currency,
    status,
    delisted_at
)
VALUES (
    'TEST-DELISTED',
    'TEST-DELISTED',
    'EQUITY',
    'USD',
    'DELISTED',
    NULL
);

-- INSTRUMENT: CHECK constraint on status/delisted_at
-- An ACTIVE instrument must not have delisted_at
INSERT INTO instrument (
    symbol,
    ticker,
    asset_class,
    quote_currency,
    status,
    delisted_at
)
VALUES (
    'TEST-ACTIVE',
    'TEST-ACTIVE',
    'EQUITY',
    'USD',
    'ACTIVE',
    CURRENT_TIMESTAMP
);


-- ORDERS

-- ORDERS: UNIQUE constraint on idempotency_key
-- MANDATORY TEST

-- Step 1: Insert an order
INSERT INTO orders (
    account_id,
    instrument_id,
    side,
    quantity,
    price,
    status,
    idempotency_key
)
VALUES (
    100001,
    1,
    'BUY',
    10,
    200.00,
    'NEW',
    'TEST-IDEMPOTENCY-001'
);

-- Step 2: Insert another order with the SAME idempotency key
INSERT INTO orders (
    account_id,
    instrument_id,
    side,
    quantity,
    price,
    status,
    idempotency_key
)
VALUES (
    100001,
    1,
    'BUY',
    10,
    200.00,
    'NEW',
    'TEST-IDEMPOTENCY-001'
);

-- ORDERS: FOREIGN KEY constraint on account_id
-- MANDATORY TEST
INSERT INTO orders (
    account_id,
    instrument_id,
    side,
    quantity,
    price,
    status,
    idempotency_key
)
VALUES (
    999999,
    1,
    'BUY',
    10,
    200.00,
    'NEW',
    'TEST-FK-ACCOUNT-001'
);

-- ORDERS: FOREIGN KEY constraint on instrument_id
INSERT INTO orders (
    account_id,
    instrument_id,
    side,
    quantity,
    price,
    status,
    idempotency_key
)
VALUES (
    100001,
    999999,
    'BUY',
    10,
    200.00,
    'NEW',
    'TEST-FK-INSTRUMENT-001'
);

-- ORDERS: CHECK constraint on side
INSERT INTO orders (
    account_id,
    instrument_id,
    side,
    quantity,
    price,
    status,
    idempotency_key
)
VALUES (
    100001,
    1,
    'HOLD',
    10,
    200.00,
    'NEW',
    'TEST-SIDE-001'
);

-- ORDERS: CHECK constraint on quantity > 0
INSERT INTO orders (
    account_id,
    instrument_id,
    side,
    quantity,
    price,
    status,
    idempotency_key
)
VALUES (
    100001,
    1,
    'BUY',
    0,
    200.00,
    'NEW',
    'TEST-QUANTITY-001'
);

-- ORDERS: CHECK constraint on price > 0
INSERT INTO orders (
    account_id,
    instrument_id,
    side,
    quantity,
    price,
    status,
    idempotency_key
)
VALUES (
    100001,
    1,
    'BUY',
    10,
    0,
    'NEW',
    'TEST-PRICE-001'
);

-- ORDERS: CHECK constraint on status
INSERT INTO orders (
    account_id,
    instrument_id,
    side,
    quantity,
    price,
    status,
    idempotency_key
)
VALUES (
    100001,
    1,
    'BUY',
    10,
    200.00,
    'PENDING',
    'TEST-STATUS-001'
);

-- ORDERS: FILLED order cannot move to another terminal state
-- Step 1: Create a FILLED order
INSERT INTO orders (
    account_id,
    instrument_id,
    side,
    quantity,
    price,
    status,
    idempotency_key
)
VALUES (
    100001,
    1,
    'BUY',
    10,
    200.00,
    'FILLED',
    'TEST-TERMINAL-001'
);

-- Step 2: Attempt to change FILLED -> REJECTED
UPDATE orders
SET status = 'REJECTED'
WHERE idempotency_key = 'TEST-TERMINAL-001';


-- HOLDING

-- HOLDING: FOREIGN KEY constraint on account_id
INSERT INTO holding (
    account_id,
    ticker,
    quantity,
    average_price,
    as_of_date
)
VALUES (
    999999,
    'AAPL',
    10,
    200.00,
    CURRENT_DATE
);

-- HOLDING: FOREIGN KEY constraint on ticker
INSERT INTO holding (
    account_id,
    ticker,
    quantity,
    average_price,
    as_of_date
)
VALUES (
    100001,
    'NOT-A-TICKER',
    10,
    200.00,
    CURRENT_DATE
);

-- HOLDING: CHECK constraint on quantity >= 0
INSERT INTO holding (
    account_id,
    ticker,
    quantity,
    average_price,
    as_of_date
)
VALUES (
    100001,
    'AAPL',
    -10,
    200.00,
    CURRENT_DATE
);

-- HOLDING: CHECK constraint on average_price >= 0
INSERT INTO holding (
    account_id,
    ticker,
    quantity,
    average_price,
    as_of_date
)
VALUES (
    100001,
    'AAPL',
    10,
    -200.00,
    CURRENT_DATE
);

-- HOLDING: UNIQUE constraint on (account_id, ticker)
INSERT INTO holding (
    account_id,
    ticker,
    quantity,
    average_price,
    as_of_date
)
VALUES (
    100001,
    'AAPL',
    10,
    200.00,
    CURRENT_DATE
);


-- POSITION

-- POSITION: FOREIGN KEY constraint on order_id
INSERT INTO position (
    order_id
)
VALUES (
    999999
);

-- POSITION: UNIQUE constraint on order_id
INSERT INTO position (
    order_id
)
VALUES (
    1
);


-- ALERTS

-- ALERTS: FOREIGN KEY constraint on account_id
INSERT INTO alerts (
    account_id,
    ticker,
    targeted_quantity,
    alert_type,
    message
)
VALUES (
    999999,
    'AAPL',
    10,
    'INFO',
    'Test alert'
);

-- ALERTS: FOREIGN KEY constraint on ticker
INSERT INTO alerts (
    account_id,
    ticker,
    targeted_quantity,
    alert_type,
    message
)
VALUES (
    100001,
    'NOT-A-TICKER',
    10,
    'INFO',
    'Test alert'
);

-- ALERTS: CHECK constraint on alert_type
INSERT INTO alerts (
    account_id,
    ticker,
    targeted_quantity,
    alert_type,
    message
)
VALUES (
    100001,
    'AAPL',
    10,
    'WARNING',
    'Invalid alert'
);

-- ALERTS: CHECK constraint on targeted_quantity > 0
INSERT INTO alerts (
    account_id,
    ticker,
    targeted_quantity,
    alert_type,
    message
)
VALUES (
    100001,
    'AAPL',
    0,
    'INFO',
    'Invalid quantity'
);


-- WATCHLIST

-- WATCHLIST: FOREIGN KEY constraint on account_id
INSERT INTO watchlist (
    account_id
)
VALUES (
    999999
);


-- WATCH_LIST_ITEMS

-- WATCH_LIST_ITEMS: FOREIGN KEY constraint on watch_list_id
INSERT INTO watch_list_items (
    watch_list_id,
    ticker
)
VALUES (
    999999,
    'AAPL'
);

-- WATCH_LIST_ITEMS: FOREIGN KEY constraint on ticker
INSERT INTO watch_list_items (
    watch_list_id,
    ticker
)
VALUES (
    1,
    'NOT-A-TICKER'
);

-- WATCH_LIST_ITEMS: PRIMARY KEY constraint on (watch_list_id, ticker)
INSERT INTO watch_list_items (
    watch_list_id,
    ticker
)
VALUES (
    1,
    'AAPL'
);


-- PERFORMANCE

-- PERFORMANCE: FOREIGN KEY constraint on account_id
INSERT INTO performance (
    account_id,
    date,
    networth,
    gain
)
VALUES (
    999999,
    CURRENT_DATE,
    25000.00,
    500.00
);

-- PERFORMANCE: PRIMARY KEY constraint on (account_id, date)
INSERT INTO performance (
    account_id,
    date,
    networth,
    gain
)
VALUES (
    100001,
    '2026-08-25',
    26000.00,
    1000.00
);