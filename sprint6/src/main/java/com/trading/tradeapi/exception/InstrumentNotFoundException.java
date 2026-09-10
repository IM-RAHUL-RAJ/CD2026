package com.trading.tradeapi.exception;

public class InstrumentNotFoundException extends DomainException {
    public InstrumentNotFoundException() {
        super("INS-404", "Instrument not found or not tradable");
    }
}
