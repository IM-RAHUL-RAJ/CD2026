package com.trading.tradeapi.dto;

import java.math.BigDecimal;

public record CreateAccountRequestDto(
        String accountName,
        Long clientId,
        String currency,
        BigDecimal initialBalance
) {}
