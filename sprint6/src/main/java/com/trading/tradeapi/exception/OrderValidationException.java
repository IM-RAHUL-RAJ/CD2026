package com.trading.tradeapi.exception;

import org.springframework.http.HttpStatus;

public class OrderValidationException extends DomainException {
    public OrderValidationException() {
        super("VAL-422", "Invalid order", HttpStatus.UNPROCESSABLE_ENTITY);
    }

    public OrderValidationException(String message) {
        super("VAL-422", message, HttpStatus.UNPROCESSABLE_ENTITY);
    }
}
