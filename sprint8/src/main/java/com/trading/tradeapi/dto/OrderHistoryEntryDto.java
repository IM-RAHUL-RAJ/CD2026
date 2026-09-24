package com.trading.tradeapi.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.trading.tradeapi.enums.OrderSide;
import com.trading.tradeapi.enums.OrderStatus;

import java.math.BigDecimal;
import java.time.Instant;

public record OrderHistoryEntryDto(
        String orderId,
        Long accountId,
        String symbol,
        OrderSide side,
        Long quantity,
        BigDecimal price,
        BigDecimal executedPrice,
        OrderStatus status,
        String idempotencyKey,
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss'Z'", timezone = "UTC")
        Instant createdOn
) {}
