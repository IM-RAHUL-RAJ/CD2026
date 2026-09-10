package com.trading.tradeapi.dto;

import com.trading.domain.order.OrderSide;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

public record PlaceOrderRequestDto(
        @NotNull(message = "Account ID is required")
        @Min(value = 1, message = "Account ID must be at least 1")
        Long accountId,

        @NotNull(message = "Symbol is required")
        @Size(min = 1, max = 20, message = "Symbol must be between 1 and 20 characters")
        String symbol,

        @NotNull(message = "Side is required")
        OrderSide side,

        @NotNull(message = "Quantity is required")
        @Min(value = 1, message = "Quantity must be at least 1")
        Long quantity,

        @NotNull(message = "Price is required")
        @DecimalMin(value = "0.01", message = "Price must be greater than 0")
        BigDecimal price,

        @NotNull(message = "Idempotency key is required")
        @Size(min = 8, max = 100, message = "Idempotency key must be between 8 and 100 characters")
        String idempotencyKey
) {}
