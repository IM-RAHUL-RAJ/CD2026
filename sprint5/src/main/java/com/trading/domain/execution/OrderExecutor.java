package com.trading.domain.execution;

import com.trading.domain.dto.OrderExecutionRequest;

// BUY and SELL fill differently, so this gets a strategy per side
public interface OrderExecutor {
    void ensureAffordable(OrderExecutionRequest request);

    void execute(OrderExecutionRequest request);
}
