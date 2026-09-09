
-- MANDATORY TEST
-- ORDERS: UNIQUE constraint on idempotency_key
INSERT INTO orders (
	order_id,
    account_id,
    instrument_id,
    side,
    quantity,
    price,
    status,
    idempotency_key
)
OVERRIDING SYSTEM VALUE
VALUES (
    1000,
    100001,
    1,
    'BUY',
    10,
    200.00,
    'NEW',
    'TEST-IDEMPOTENCY-0010'
);

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
    'TEST-IDEMPOTENCY-0010'
);
----------------------------------------------------------------------------------
-- ORDERS: FOREIGN KEY constraint on account_id
INSERT INTO orders (
	order_id,
    account_id,
    instrument_id,
    side,
    quantity,
    price,
    status,
    idempotency_key
)
OVERRIDING SYSTEM VALUE
VALUES (
	1002,
    999999,
    1,
    'BUY',
    10,
    200.00,
    'NEW',
    'TEST-FK-ACCOUNT-001'
);
------------------------------------------------------------------------------------------
------------------------------------------------------------------------------------------


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
------------------------------------------------------

-- ORDERS: FILLED order cannot move to another terminal state
INSERT INTO orders (
	order_id, 
    account_id,
    instrument_id,
    side,
    quantity,
    price,
    status,
    idempotency_key
)
overriding system value
VALUES (
	1003,
    100001,
    1,
    'BUY',
    10,
    200.00,
    'FILLED',
    'TEST-TERMINAL-001'
);

UPDATE orders
SET status = 'REJECTED'
WHERE idempotency_key = 'TEST-TERMINAL-001';



