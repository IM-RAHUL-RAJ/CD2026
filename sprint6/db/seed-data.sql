-- Seed data for development/testing

-- Create a client and an account with account_id = 1
INSERT INTO client (client_id, name)
VALUES (1001, 'Test Client')
ON CONFLICT (client_id) DO NOTHING;

INSERT INTO account (account_id, account_name, client_id, currency, cash_balance, status, version)
VALUES (1, 'Test Account 1', 1001, 'USD', 100000.00, 'ACTIVE', 0)
ON CONFLICT (account_id) DO NOTHING;

-- Instruments
INSERT INTO instrument (instrument_id, symbol, ticker, asset_class, quote_currency, status)
VALUES (1, 'AAPL', 'AAPL', 'EQUITY', 'USD', 'ACTIVE')
ON CONFLICT (instrument_id) DO NOTHING;

INSERT INTO instrument (instrument_id, symbol, ticker, asset_class, quote_currency, status)
VALUES (2, 'MSFT', 'MSFT', 'EQUITY', 'USD', 'ACTIVE')
ON CONFLICT (instrument_id) DO NOTHING;

-- Holdings for account 1
INSERT INTO holding (holding_id, account_id, ticker, quantity, average_price, as_of_date)
VALUES (1, 1, 'AAPL', 50.00000000, 150.00, CURRENT_DATE)
ON CONFLICT (holding_id) DO NOTHING;

-- Example order
INSERT INTO orders (order_id, account_id, instrument_id, side, quantity, price, status, received_at, idempotency_key, executed_price, executed_on, rejection_reason)
VALUES (1, 1, 1, 'BUY', 10.00000000, 150.00, 'FILLED', now(), 'seed-1', 150.00, now(), NULL)
ON CONFLICT (order_id) DO NOTHING;
