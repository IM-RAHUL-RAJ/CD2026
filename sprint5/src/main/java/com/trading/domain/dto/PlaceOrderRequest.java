package com.trading.domain.dto;

import com.trading.domain.order.OrderSide;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

public record PlaceOrderRequest(
        @NotNull @Min(1) Long accountId,
        @NotBlank @Size(max = 20) String symbol,
        @NotNull OrderSide side,
        @NotNull @Min(1) Long quantity,
        @NotNull @DecimalMin(value = "0.0", inclusive = false)
        @Digits(integer = 18, fraction = 2) BigDecimal price,
        @NotBlank @Size(min = 8, max = 100) String idempotencyKey) {
        }