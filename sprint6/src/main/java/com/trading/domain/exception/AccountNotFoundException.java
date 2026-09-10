package com.trading.domain.exception;

public class AccountNotFoundException extends DomainException {
    public AccountNotFoundException() {
        super("ACC-404", "Account not found");
    }
}
