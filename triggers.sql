
------------------------------------------------------------
-- 1. A CLOSED account can never be reopened.
-- 2. Automatically record when an account is closed.
-- 3. Automatically record when an instrument is delisted.
-- 4. Maintain terminal order status.
------------------------------------------------------------


-- Account Status Transition Trigger: A CLOSED account can never be reopened.
CREATE OR REPLACE FUNCTION validate_account_status_transition()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF OLD.status = 'CLOSED'
       AND NEW.status <> OLD.status THEN

        RAISE EXCEPTION
            'Account % is already CLOSED and cannot be reopened',
            OLD.account_id;

    END IF;

    RETURN NEW;
END;
$$;


CREATE TRIGGER trg_validate_account_status_transition
BEFORE UPDATE OF status
ON account
FOR EACH ROW
EXECUTE FUNCTION validate_account_status_transition();



-- Automatically record when an account is closed.

CREATE OR REPLACE FUNCTION maintain_account_closed_at()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.status = 'CLOSED' AND OLD.status <> 'CLOSED' THEN
        NEW.closed_at = CURRENT_TIMESTAMP;
    END IF;

    RETURN NEW;
END;
$$;


CREATE TRIGGER trg_maintain_account_closed_at
BEFORE UPDATE OF status
ON account
FOR EACH ROW
EXECUTE FUNCTION maintain_account_closed_at();


-- Automatically record when an instrument is delisted.

CREATE OR REPLACE FUNCTION maintain_instrument_delisted_at()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.status = 'DELISTED' AND OLD.status <> 'DELISTED' THEN
        NEW.delisted_at = CURRENT_TIMESTAMP;
    END IF;

    RETURN NEW;
END;
$$;


CREATE TRIGGER trg_maintain_instrument_delisted_at
BEFORE UPDATE OF status
ON instrument
FOR EACH ROW
EXECUTE FUNCTION maintain_instrument_delisted_at();

-- Maintain terminal order status

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


CREATE TRIGGER trg_validate_order_status_transition
BEFORE UPDATE OF status
ON orders
FOR EACH ROW
EXECUTE FUNCTION validate_order_status_transition();