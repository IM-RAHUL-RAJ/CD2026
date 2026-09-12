package com.trading.tradeapi.exception;

public class OrderValidationException extends DomainException {
    public OrderValidationException() {
        super("VAL-422", "Invalid order");
    }

    public OrderValidationException(String message) {
        super("VAL-422", message);
    }
}
