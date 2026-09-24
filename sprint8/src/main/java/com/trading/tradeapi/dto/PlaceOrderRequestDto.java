package com.trading.tradeapi.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.trading.tradeapi.enums.OrderSide;
import com.trading.tradeapi.enums.OrderType;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

@JsonIgnoreProperties(ignoreUnknown = true)
public record PlaceOrderRequestDto(
        @NotNull(message = "Account ID is required")
        @Min(value = 1, message = "Account ID must be at least 1")
        Long accountId,

        @NotNull(message = "Ticker is required")
        @Size(min = 1, max = 20, message = "Ticker must be between 1 and 20 characters")
        @JsonProperty("symbol")
        String ticker,

        @NotNull(message = "Side is required")
        OrderSide side,

        @NotNull(message = "Quantity is required")
        @Min(value = 1, message = "Quantity must be at least 1")
        Long quantity,

        @DecimalMin(value = "0", message = "Price must be non-negative")
        BigDecimal price,

        @NotNull(message = "Order type is required")
        OrderType orderType,

        @NotNull(message = "Idempotency key is required")
        @Size(min = 8, max = 100, message = "Idempotency key must be between 8 and 100 characters")
        String idempotencyKey
) {}
