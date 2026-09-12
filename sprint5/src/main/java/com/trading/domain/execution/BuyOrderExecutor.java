package com.trading.domain.execution;

import com.trading.domain.dto.OrderExecutionRequest;
import com.trading.domain.exception.InsufficientFundsException;
import com.trading.domain.exception.InsufficientInstrumentQuantityException;
import com.trading.domain.order.Order;

public class BuyOrderExecutor implements OrderExecutor {

    @Override
    public void ensureAffordable(OrderExecutionRequest request) {
        Order order = request.order();

        if (!request.account().canAfford(order.getOrderAmount())) {
            throw new InsufficientFundsException();
        }
    }

    @Override
    public void execute(OrderExecutionRequest request) {
        ensureAffordable(request);

        Order order = request.order();

        if (!request.instrument().hasQuantity(order.getQuantity())) {
            throw new InsufficientInstrumentQuantityException();
        }

        request.account().debit(order.getOrderAmount());
        request.instrument().reserveQuantity(order.getQuantity());
        request.holdings().buy(order.getQuantity(), order.getPrice());

        order.markFilled();
    }
}
