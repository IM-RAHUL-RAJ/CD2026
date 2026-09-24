-- ============================================================================
-- Sprint 8 seed data (run AFTER db/schema.sql against trading_system_db)
-- Demo credentials: username/email demo / demo@example.com, password Capstone@2026
-- ============================================================================

-- Demo user (auth schema). password_hash is bcrypt(cost 12) of "Capstone@2026".
INSERT INTO auth.users (uuid, first_name, middle_name, last_name, username, email, password_hash, roles)
VALUES (gen_random_uuid(), 'Demo', NULL, 'Investor', 'demo', 'demo@example.com',
        '$2b$12$iu91u5d649dR7rDFH0v1AOs0R7fp1XB4cLolpmD7UoeHFOJzR/qJ6',
        ARRAY['CUSTOMER']);

-- Trading account for the demo user (user_id 1, account_id 1): starts at 100000.
INSERT INTO trading.account (user_id, currency, cash_balance, status, version)
VALUES (1, 'USD', 100000.00, 'ACTIVE', 1);

-- Instruments
INSERT INTO trading.instrument (symbol, ticker, asset_class, quote_currency, status)
VALUES ('AAPL', 'AAPL', 'EQUITY', 'USD', 'ACTIVE'),
       ('MSFT', 'MSFT', 'EQUITY', 'USD', 'ACTIVE'),
       ('GOOG', 'GOOG', 'EQUITY', 'USD', 'ACTIVE'),
       ('AMZN', 'AMZN', 'EQUITY', 'USD', 'ACTIVE'),
       ('TSLA', 'TSLA', 'EQUITY', 'USD', 'ACTIVE');

-- Opening holding
INSERT INTO trading.holding (account_id, ticker, quantity, average_price, as_of_date)
VALUES (1, 'AAPL', 50.00000000, 150.00000000, CURRENT_DATE);

-- Sample filled order for the demo account
INSERT INTO trading.orders (account_id, ticker, side, quantity, price, order_type, status,
                            received_at, idempotency_key, executed_price, executed_on)
VALUES (1, 'AAPL', 'BUY', 10.00000000, 150.00000000, 'LIMIT', 'FILLED',
        now(), 'seed-0001', 150.00000000, now());

-- Keep sequences in sync with the seeded rows so later inserts don't clash.
SELECT setval('auth.users_user_id_seq', (SELECT COALESCE(MAX(user_id), 1) FROM auth.users));
SELECT setval('trading.account_account_id_seq', (SELECT COALESCE(MAX(account_id), 1) FROM trading.account));
SELECT setval('trading.instrument_instrument_id_seq', (SELECT COALESCE(MAX(instrument_id), 1) FROM trading.instrument));
SELECT setval('trading.holding_holding_id_seq', (SELECT COALESCE(MAX(holding_id), 1) FROM trading.holding));
SELECT setval('trading.orders_order_id_seq', (SELECT COALESCE(MAX(order_id), 1) FROM trading.orders));