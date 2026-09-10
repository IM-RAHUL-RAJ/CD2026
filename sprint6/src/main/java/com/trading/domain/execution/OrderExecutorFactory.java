package com.trading.domain.execution;

import com.trading.domain.order.OrderSide;

public final class OrderExecutorFactory {
    private static final OrderExecutor BUY_EXECUTOR = new BuyOrderExecutor();
    private static final OrderExecutor SELL_EXECUTOR = new SellOrderExecutor();

    private OrderExecutorFactory() {
    }

    public static OrderExecutor forSide(OrderSide side) {
        return switch (side) {
            case BUY -> BUY_EXECUTOR;
            case SELL -> SELL_EXECUTOR;
        };
    }
}
