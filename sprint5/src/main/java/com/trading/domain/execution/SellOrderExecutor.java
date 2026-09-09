package com.trading.domain.execution;

import com.trading.domain.dto.OrderExecutionRequest;
import com.trading.domain.exception.InsufficientHoldingsException;
import com.trading.domain.holdings.Holdings;
import com.trading.domain.order.Order;

public class SellOrderExecutor implements OrderExecutor {

    @Override
    public void ensureAffordable(OrderExecutionRequest request) {
        Holdings holdings = request.holdings();

        if (holdings == null || !holdings.canSell(request.order().getQuantity())) {
            throw new InsufficientHoldingsException();
        }
    }

    @Override
    public void execute(OrderExecutionRequest request) {
        ensureAffordable(request);

        Order order = request.order();

        request.holdings().sell(order.getQuantity());
        request.account().credit(order.getOrderAmount());

        order.markFilled();
    }
}
