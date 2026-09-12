package com.trading.tradeapi.exception;

public class InsufficientHoldingsException extends DomainException {
    public InsufficientHoldingsException() {
        super("ORD-409", "Insufficient holdings");
    }
}
