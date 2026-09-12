package com.trading.tradeapi.exception;

public class DuplicateOrderException extends DomainException {
    public DuplicateOrderException() {
        super("ORD-409", "Duplicate order");
    }

    public DuplicateOrderException(String code, String message) {
        super(code, message);
    }
}
