-- Test seed data for H2

-- Insert test client (letting the database auto-generate client_id)
INSERT INTO client (name) VALUES ('Test Client');

-- Insert test account (letting the database auto-generate account_id)
INSERT INTO account (account_name, client_id, currency, cash_balance, status, version)
VALUES ('TestAccount', 1, 'USD', 10000.00, 'ACTIVE', 0);

-- Insert test account (for slice tests, letting the database auto-generate account_id)
INSERT INTO account (account_name, client_id, currency, cash_balance, status, version)
VALUES ('TestAccount2', 1, 'USD', 5000.00, 'ACTIVE', 0);

-- Insert test instruments (letting the database auto-generate instrument_id)
INSERT INTO instrument (symbol, ticker, asset_class, quote_currency, status)
VALUES ('AAPL', 'AAPL', 'EQUITY', 'USD', 'ACTIVE');

INSERT INTO instrument (symbol, ticker, asset_class, quote_currency, status)
VALUES ('GOOGL', 'GOOGL', 'EQUITY', 'USD', 'ACTIVE');

INSERT INTO instrument (symbol, ticker, asset_class, quote_currency, status)
VALUES ('DELISTED', 'DELISTED', 'EQUITY', 'USD', 'DELISTED');

-- Insert test holdings (letting the database auto-generate holding_id)
INSERT INTO holding (account_id, ticker, quantity, average_price, as_of_date)
VALUES (1, 'AAPL', 100.00000000, 150.00000000, CAST(CURRENT_DATE AS DATE));

-- Insert test orders (letting the database auto-generate order_id)
INSERT INTO orders (account_id, instrument_id, side, quantity, price, status, received_at, idempotency_key)
VALUES (1, 1, 'BUY', 10.00000000, 150.00000000, 'FILLED', CURRENT_TIMESTAMP, 'idempotency-key-1');

INSERT INTO orders (account_id, instrument_id, side, quantity, price, status, received_at, idempotency_key)
VALUES (1, 1, 'SELL', 5.00000000, 160.00000000, 'NEW', CURRENT_TIMESTAMP, 'idempotency-key-2');
