package com.trading.tradeapi.exception;

import org.springframework.http.HttpStatus;

public class UnauthorizedAccountAccessException extends DomainException {
    public UnauthorizedAccountAccessException() {
        super("ACC-401", "Unauthorized account access", HttpStatus.UNAUTHORIZED);
    }
}
