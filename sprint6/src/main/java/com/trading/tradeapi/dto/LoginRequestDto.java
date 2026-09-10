package com.trading.tradeapi.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record LoginRequestDto(
        @NotNull(message = "Account ID is required")
        @Min(value = 1, message = "Account ID must be at least 1")
        Long accountId
) {}
