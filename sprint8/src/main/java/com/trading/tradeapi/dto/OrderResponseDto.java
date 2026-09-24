package com.trading.tradeapi.dto;

import com.trading.tradeapi.enums.OrderSide;
import com.trading.tradeapi.enums.OrderStatus;

import java.math.BigDecimal;

public record OrderResponseDto(
        String orderId,
        OrderStatus status,
        String message,
        String symbol,
        OrderSide side,
        Long quantity,
        BigDecimal price,
        BigDecimal executedPrice
) {}
