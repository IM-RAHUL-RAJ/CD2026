package com.trading.tradeapi.exception;

public class AccountNotActiveException extends DomainException {
    public AccountNotActiveException() {
        super("ACC-403", "Account is not active");
    }
}
