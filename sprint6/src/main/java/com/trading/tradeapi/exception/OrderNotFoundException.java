package com.trading.tradeapi.exception;

import org.springframework.http.HttpStatus;

public class OrderNotFoundException extends DomainException {
    public OrderNotFoundException() {
        super("ORD-409", "Order not found", HttpStatus.CONFLICT);
    }

    public OrderNotFoundException(String message) {
        super("ORD-409", message, HttpStatus.CONFLICT);
    }
}
