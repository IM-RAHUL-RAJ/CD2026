package com.trading.tradeapi.dto;

import com.trading.domain.order.OrderSide;
import com.trading.domain.order.OrderStatus;

import java.math.BigDecimal;

public record OrderResponseDto(
        String orderId,
        OrderStatus status,
        String message,
        String symbol,
        OrderSide side,
        Long quantity,
        BigDecimal price
) {}
