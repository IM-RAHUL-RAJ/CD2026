package com.trading.tradeapi.service;

import com.trading.tradeapi.domain.Account;
import com.trading.tradeapi.enums.AccountStatus;
import com.trading.tradeapi.enums.OrderSide;
import com.trading.tradeapi.enums.OrderStatus;
import com.trading.tradeapi.dto.AccountResponseDto;
import com.trading.tradeapi.dto.BalanceResponseDto;
import com.trading.tradeapi.dto.OrderHistoryEntryDto;
import com.trading.tradeapi.dto.OrderResponseDto;
import com.trading.tradeapi.dto.PositionResponseDto;
import com.trading.tradeapi.entity.AccountRecord;
import com.trading.tradeapi.entity.HoldingRecord;
import com.trading.tradeapi.entity.InstrumentRecord;
import com.trading.tradeapi.entity.OrderRecord;
import com.trading.tradeapi.exception.AccountNotActiveException;
import com.trading.tradeapi.exception.AccountNotFoundException;
import com.trading.tradeapi.exception.DuplicateOrderException;
import com.trading.tradeapi.exception.InstrumentNotFoundException;
import com.trading.tradeapi.exception.InsufficientFundsException;
import com.trading.tradeapi.exception.InsufficientHoldingsException;
import com.trading.tradeapi.exception.OrderNotFoundException;
import com.trading.tradeapi.exception.OrderValidationException;
import com.trading.tradeapi.kafka.KafkaOrderPublisher;
import com.trading.tradeapi.mapper.AccountMapper;
import com.trading.tradeapi.mapper.HoldingMapper;
import com.trading.tradeapi.mapper.InstrumentMapper;
import com.trading.tradeapi.mapper.OrderMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;

@Service
public class TradeService {

    private final AccountMapper accountMapper;
    private final InstrumentMapper instrumentMapper;
    private final OrderMapper orderMapper;
    private final HoldingMapper holdingMapper;
    private final KafkaOrderPublisher kafkaOrderPublisher;

    public TradeService(AccountMapper accountMapper,
                        InstrumentMapper instrumentMapper,
                        OrderMapper orderMapper,
                        HoldingMapper holdingMapper,
                        KafkaOrderPublisher kafkaOrderPublisher) {
        this.accountMapper = accountMapper;
        this.instrumentMapper = instrumentMapper;
        this.orderMapper = orderMapper;
        this.holdingMapper = holdingMapper;
        this.kafkaOrderPublisher = kafkaOrderPublisher;
    }

    @Transactional
    public OrderResponseDto placeOrder(Long accountId, String symbol, OrderSide side,
                                     Long quantity, BigDecimal price, String idempotencyKey) {

        // Rule 1: Account must exist
        AccountRecord accRecord = accountMapper.findById(accountId);
        if (accRecord == null) {
            throw new AccountNotFoundException();
        }

        // Rule 2: Account must be ACTIVE
        if (accRecord.getStatus() != AccountStatus.ACTIVE) {
            throw new AccountNotActiveException();
        }

        // Rule 3: Instrument must exist and be tradable
        InstrumentRecord instRecord = instrumentMapper.findBySymbol(symbol);
        if (instRecord == null || "DELISTED".equalsIgnoreCase(instRecord.getStatus())) {
            throw new InstrumentNotFoundException();
        }

        // Rule 4 & 5: Field validations
        if (quantity == null || quantity <= 0) {
            throw new OrderValidationException("Quantity must be greater than zero");
        }
        if (price == null || price.compareTo(BigDecimal.ZERO) <= 0) {
            throw new OrderValidationException("Price must be greater than zero");
        }

        // Rule 8: Check idempotency key uniqueness
        OrderRecord existingOrder = orderMapper.findByIdempotencyKey(idempotencyKey);
        if (existingOrder != null) {
            throw new DuplicateOrderException();
        }

        Account domainAccount = new Account(
                accRecord.getAccountId(),
                accRecord.getAccountName(),
                accRecord.getCashBalance(),
                accRecord.getStatus()
        );

        String ticker = instRecord.getTicker() != null ? instRecord.getTicker() : instRecord.getSymbol();

        if (side == OrderSide.BUY) {
            BigDecimal totalCost = price.multiply(BigDecimal.valueOf(quantity)).setScale(2, RoundingMode.HALF_UP);
            if (!domainAccount.canAfford(totalCost)) {
                throw new InsufficientFundsException();
            }
        } else if (side == OrderSide.SELL) {
            HoldingRecord holding = holdingMapper.findByAccountAndTicker(accountId, ticker);
            if (holding == null || holding.getQuantity().compareTo(BigDecimal.valueOf(quantity)) < 0) {
                throw new InsufficientHoldingsException();
            }
        }

        Instant createdOn = Instant.now();
        OrderRecord orderRecord = new OrderRecord(
                null, accountId, instRecord.getInstrumentId(), side,
                BigDecimal.valueOf(quantity), price, OrderStatus.NEW,
                createdOn, idempotencyKey
        );

        try {
            orderMapper.insertOrder(orderRecord);
        } catch (Exception e) {
            throw new DuplicateOrderException();
        }

        final Long generatedOrderId = orderRecord.getOrderId();

        // Publish event AFTER database commit (Never publish inside transaction)
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    kafkaOrderPublisher.publishOrderPlacedEvent(
                            generatedOrderId,
                            accountId,
                            symbol,
                            side.name(),
                            quantity,
                            price,
                            idempotencyKey,
                            createdOn
                    );
                }
            });
        } else {
            kafkaOrderPublisher.publishOrderPlacedEvent(
                    generatedOrderId,
                    accountId,
                    symbol,
                    side.name(),
                    quantity,
                    price,
                    idempotencyKey,
                    createdOn
            );
        }

        return new OrderResponseDto(
                "ORD-" + idempotencyKey,
                OrderStatus.NEW,
                "Order recorded awaiting execution",
                symbol,
                side,
                quantity,
                price
        );
    }

    @Transactional
    public OrderResponseDto cancelOrder(String id) {
        OrderRecord order = orderMapper.findById(id);
        if (order == null) {
            throw new OrderNotFoundException();
        }
        if (order.getStatus() != OrderStatus.NEW) {
            throw new DuplicateOrderException("ORD-409", "Order is not cancellable");
        }

        int rows = orderMapper.cancelOrder(id);
        if (rows == 0) {
            throw new DuplicateOrderException("ORD-409", "Order is not cancellable");
        }

        InstrumentRecord inst = instrumentMapper.findById(order.getInstrumentId());
        String symbol = inst != null ? inst.getSymbol() : "UNKNOWN";

        return new OrderResponseDto(
                "ORD-" + id,
                OrderStatus.CANCELLED,
                "Order cancelled",
                symbol,
                order.getSide(),
                order.getQuantity().longValue(),
                order.getPrice()
        );
    }

    @Transactional(readOnly = true)
    public AccountResponseDto getAccount(Long id) {
        AccountRecord acc = accountMapper.findById(id);
        if (acc == null) {
            throw new AccountNotFoundException();
        }
        return new AccountResponseDto(
                acc.getAccountId(),
                acc.getAccountName(),
                acc.getClientName(),
                acc.getCashBalance(),
                acc.getStatus(),
                acc.getVersion(),
                Instant.now()
        );
    }

    @Transactional(readOnly = true)
    public BalanceResponseDto getBalance(Long id) {
        AccountRecord acc = accountMapper.findById(id);
        if (acc == null) {
            throw new AccountNotFoundException();
        }
        return new BalanceResponseDto(
                acc.getAccountId(),
                acc.getCashBalance(),
                acc.getCurrency(),
                Instant.now()
        );
    }

    @Transactional(readOnly = true)
    public List<PositionResponseDto> getPositions(Long id) {
        AccountRecord acc = accountMapper.findById(id);
        if (acc == null) {
            throw new AccountNotFoundException();
        }
        return holdingMapper.findPositionsByAccountId(id);
    }

    @Transactional(readOnly = true)
    public List<OrderHistoryEntryDto> getOrders(Long id, String status, Instant from, Instant to) {
        AccountRecord acc = accountMapper.findById(id);
        if (acc == null) {
            throw new AccountNotFoundException();
        }
        return orderMapper.findByAccountAndFilters(id, status, from, to);
    }
}
