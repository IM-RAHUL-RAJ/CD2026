package com.trading.tradeapi.exception;

import com.trading.tradeapi.exception.DomainException;

public class OrderNotFoundException extends DomainException {
    public OrderNotFoundException() {
        super("ORD-409", "Order not found");
    }

    public OrderNotFoundException(String message) {
        super("ORD-409", message);
    }
}
