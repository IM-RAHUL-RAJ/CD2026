package com.trading.tradeapi.exception;

public class AccountNotFoundException extends DomainException {
    public AccountNotFoundException() {
        super("ACC-404", "Account not found");
    }
}
