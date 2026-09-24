package com.trading.tradeapi.dto;

import java.math.BigDecimal;

public record CreateAccountRequestDto(
        Long userId,
        String currency,
        BigDecimal initialBalance
) {}