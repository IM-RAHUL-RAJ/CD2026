package com.trading.tradeapi.exception;

public class InsufficientFundsException extends DomainException {
    public InsufficientFundsException() {
        super("ORD-400", "Insufficient funds");
    }
}
