-- Test seed data for H2

-- Insert test client
INSERT INTO client (client_id, name) VALUES (1, 'Test Client');

-- Insert test account
INSERT INTO account (account_id, account_name, client_id, currency, cash_balance, status, version)
VALUES (1, 'TestAccount', 1, 'USD', 10000.00, 'ACTIVE', 0);

-- Insert test account (for slice tests)
INSERT INTO account (account_id, account_name, client_id, currency, cash_balance, status, version)
VALUES (2, 'TestAccount2', 1, 'USD', 5000.00, 'ACTIVE', 0);

-- Insert test instruments
INSERT INTO instrument (instrument_id, symbol, ticker, asset_class, quote_currency, status)
VALUES (1, 'AAPL', 'AAPL', 'EQUITY', 'USD', 'ACTIVE');

INSERT INTO instrument (instrument_id, symbol, ticker, asset_class, quote_currency, status)
VALUES (2, 'GOOGL', 'GOOGL', 'EQUITY', 'USD', 'ACTIVE');

INSERT INTO instrument (instrument_id, symbol, ticker, asset_class, quote_currency, status)
VALUES (3, 'DELISTED', 'DELISTED', 'EQUITY', 'USD', 'DELISTED');

-- Insert test holdings
INSERT INTO holding (holding_id, account_id, ticker, quantity, average_price, as_of_date)
VALUES (1, 1, 'AAPL', 100.00000000, 150.00000000, CAST(CURRENT_DATE AS DATE));

-- Insert test orders
INSERT INTO orders (order_id, account_id, instrument_id, side, quantity, price, status, received_at, idempotency_key)
VALUES (1, 1, 1, 'BUY', 10.00000000, 150.00000000, 'FILLED', CURRENT_TIMESTAMP, 'idempotency-key-1');

INSERT INTO orders (order_id, account_id, instrument_id, side, quantity, price, status, received_at, idempotency_key)
VALUES (2, 1, 1, 'SELL', 5.00000000, 160.00000000, 'NEW', CURRENT_TIMESTAMP, 'idempotency-key-2');
