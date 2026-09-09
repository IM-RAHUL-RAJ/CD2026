package com.trading.domain.dto;

import com.trading.domain.account.Account;
import com.trading.domain.holdings.Holdings;
import com.trading.domain.instrument.Instrument;
import com.trading.domain.order.Order;

// groups everything an OrderExecutor needs so we're not passing 4 args around
public record OrderExecutionRequest(
        Order order,
        Account account,
        Instrument instrument,
        Holdings holdings) {
}