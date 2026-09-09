package com.trading.domain.exception;

public class InsufficientInstrumentQuantityException extends DomainException {
    public InsufficientInstrumentQuantityException() {
        super("ORD-409", "Insufficient instrument quantity");
    }
}
