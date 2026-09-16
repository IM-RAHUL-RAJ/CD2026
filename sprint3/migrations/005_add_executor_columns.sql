-- =============================================================================
-- Migration 005: Add Trade Executor Outcome Columns to Orders Table
-- Sprint 7 Deliverable
-- Adds executed_price, executed_on, and rejection_reason for execution settlement.
-- =============================================================================

ALTER TABLE orders
    ADD COLUMN IF NOT EXISTS executed_price NUMERIC(18,8),
    ADD COLUMN IF NOT EXISTS executed_on TIMESTAMP WITH TIME ZONE,
    ADD COLUMN IF NOT EXISTS rejection_reason VARCHAR(255);
