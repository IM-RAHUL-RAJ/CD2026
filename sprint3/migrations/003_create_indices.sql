-- Finds an account's NEW orders and returns newest first
CREATE INDEX idx_orders_account_status_received
ON orders (account_id, status, received_at DESC);

-- Finds orders after a given timestamp efficiently
CREATE INDEX idx_orders_received_at
ON orders (received_at);

-- Finds performance records for an account by date, newest first
CREATE INDEX idx_performance_account_date
ON performance (account_id, date DESC);