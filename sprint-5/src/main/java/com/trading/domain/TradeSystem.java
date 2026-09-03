package com.trading.domain;

import com.trading.domain.account.Account;
import com.trading.domain.account.AccountManager;
import com.trading.domain.dto.HoldingsUpdateRequest;
import com.trading.domain.dto.OrderExecutionRequest;
import com.trading.domain.dto.PlaceOrderRequest;
import com.trading.domain.execution.OrderExecutor;
import com.trading.domain.execution.OrderExecutorFactory;
import com.trading.domain.exception.InstrumentNotFoundException;
import com.trading.domain.holdings.Holdings;
import com.trading.domain.instrument.Instrument;
import com.trading.domain.order.Order;
import com.trading.domain.order.OrderValidationService;
import com.trading.domain.position.Position;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class TradeSystem {
    private final AccountManager accountManager;
    private final OrderValidationService orderValidationService;

    private final Map<Long, Instrument> instruments;
    private final Map<String, Holdings> holdings;
    private final Map<Long, Position> positions;
    private final Set<String> acceptedIdempotencyKeys;

    public TradeSystem(Map<Long, Account> accounts,
                       Map<Long, Instrument> instruments) {
        this.instruments = instruments;
        this.holdings = new HashMap<>();
        this.positions = new HashMap<>();
        this.acceptedIdempotencyKeys = new HashSet<>();

        this.accountManager = new AccountManager(accounts);
        this.orderValidationService =
                new OrderValidationService(instruments, acceptedIdempotencyKeys);
    }

    public void placeOrder(long orderId, PlaceOrderRequest request) {
        Account account =
                accountManager.isAccountValid(request.accountId());

        Instrument instrument = findInstrumentBySymbol(request.symbol());

        Order order = new Order(orderId, instrument.getInstrumentId(), request);

        orderValidationService.isOrderValid(order);

        String holdingsKey =
                order.getAccountId() + ":" + order.getInstrumentId();

        Holdings currentHoldings = holdings.get(holdingsKey);

        OrderExecutor orderExecutor =
                OrderExecutorFactory.forSide(order.getOrderSide());

        Holdings targetHoldings = currentHoldings;

        if (targetHoldings == null) {
            targetHoldings = new Holdings(new HoldingsUpdateRequest(
                    order.getAccountId(),
                    order.getInstrumentId(),
                    0,
                    order.getPrice()
            ));
        }

        OrderExecutionRequest executionRequest = new OrderExecutionRequest(
                order, account, instrument, targetHoldings);

        orderExecutor.ensureAffordable(executionRequest);

        // position is just a marker for now, no 24hr lifecycle yet
        Position position = new Position(order.getOrderId());

        // this updates cash, inventory and holdings all at once
        orderExecutor.execute(executionRequest);

        holdings.put(holdingsKey, targetHoldings);
        positions.put(order.getOrderId(), position);
        orderValidationService.acceptIdempotencyKey(order);
    }

    private Instrument findInstrumentBySymbol(String symbol) {
        return instruments.values().stream()
                .filter(instrument -> instrument.getSymbol().equals(symbol))
                .findFirst()
                .orElseThrow(InstrumentNotFoundException::new);
    }

    public Map<Long, Position> getPositions() {
        return Map.copyOf(positions);
    }

    public Map<String, Holdings> getHoldings() {
        return Map.copyOf(holdings);
    }
}
