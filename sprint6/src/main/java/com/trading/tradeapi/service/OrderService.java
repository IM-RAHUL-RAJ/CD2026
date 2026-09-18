package com.trading.tradeapi.service;

import com.trading.tradeapi.exception.DuplicateOrderException;
import com.trading.tradeapi.enums.OrderStatus;
import com.trading.tradeapi.dto.OrderHistoryEntryDto;
import com.trading.tradeapi.dto.OrderResponseDto;
import com.trading.tradeapi.dto.PlaceOrderRequestDto;
import com.trading.tradeapi.entity.AccountRecord;
import com.trading.tradeapi.entity.InstrumentRecord;
import com.trading.tradeapi.entity.OrderRecord;
import com.trading.tradeapi.exception.OrderNotFoundException;
import com.trading.tradeapi.kafka.producer.OrderProducer;
import com.trading.tradeapi.mapper.AccountMapper;
import com.trading.tradeapi.mapper.HoldingMapper;
import com.trading.tradeapi.mapper.OrderMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

@Service
public class OrderService {
    private static final Logger logger = LoggerFactory.getLogger(OrderService.class);

    private final OrderMapper orderMapper;
    private final AccountService accountService;
    private final OrderProducer orderProducer;


    public OrderService(InstrumentService instrumentService,
                        AccountMapper accountMapper,
                        OrderMapper orderMapper,
                        HoldingMapper holdingMapper,
                        @Lazy AccountService accountService,
                        OrderProducer orderProducer) {
        this.orderMapper = orderMapper;
        this.accountService = accountService;
        this.orderProducer = orderProducer;
    }

    @Transactional
    public OrderResponseDto placeOrder(PlaceOrderRequestDto request) {
        
        validateOrder(request);

        Instant receivedAt = Instant.now();

        String orderId = insertOrderAndGetId(request, receivedAt);

        // Publish order to Kafka
        publishOrderToKafka(orderId, request, receivedAt);

        // Return response with NEW status
        return new OrderResponseDto(
                orderId,
                OrderStatus.NEW,
                "Order placed successfully",
                request.ticker(),
                request.side(),
                request.quantity(),
                request.price(),
                null  // executedPrice not available yet - executor will fill it
        );
    }

    @Transactional
    private String insertOrderAndGetId(PlaceOrderRequestDto request, Instant receivedAt) {
        OrderRecord orderRecord = new OrderRecord(
                null, 
                request.accountId(),
                request.ticker(),
                request.side(),
                BigDecimal.valueOf(request.quantity()),
                request.price(),
                OrderStatus.NEW,
                request.orderType(),
                receivedAt,
                request.idempotencyKey()
        );

        int rows = orderMapper.insertOrder(orderRecord);
        if (rows == 0) {
            throw new RuntimeException("Failed to insert order into database");
        }

        // Get the generated orderId from the orderRecord (populated by MyBatis as BIGSERIAL)
        // Return as String for API response, but it's the numeric order_id from DB
        Long generatedOrderId = orderRecord.getOrderId();
        logger.info("Order inserted with numeric ID: {}", generatedOrderId);
        return generatedOrderId.toString();
    }

    private void publishOrderToKafka(String orderId, PlaceOrderRequestDto request, Instant receivedAt) {
        try {
            orderProducer.publishOrderPlaced(
                    Long.parseLong(orderId),  // Convert String back to Long for Kafka publisher
                    request.accountId(),
                    request.ticker(),
                    request.side().toString(),
                    request.quantity(),
                    request.price(),
                    request.idempotencyKey(),
                    receivedAt
            );
        } catch (Exception e) {
            logger.error("Failed to publish order to Kafka, but order was saved: {}", e.getMessage());
            // Don't throw exception - order is already saved in database
            // Publishing after commit is recoverable (order saved but not published)
        }
    }

    @Transactional
    private void validateOrder(PlaceOrderRequestDto request) {
        // Check for duplicate order using idempotency key
        OrderRecord existingOrder = orderMapper.findByIdempotencyKey(request.idempotencyKey());
        if (existingOrder != null) {
            logger.warn("Duplicate order attempt with idempotency key: {}", request.idempotencyKey());
            throw new DuplicateOrderException("ORD-409", "Order with this idempotency key already exists");
        }

        // Validate account exists and is active
        AccountRecord account = accountService.getAccountRecord(request.accountId());
        accountService.validateAccountActive(account);
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

        return new OrderResponseDto(
                "ORD-" + id,
                OrderStatus.CANCELLED,
                "Order cancelled",
                order.getTicker(),
                order.getSide(),
                order.getQuantity().longValue(),
                order.getPrice(),
                order.getExecutedPrice()
        );
    }

    @Transactional(readOnly = true)
    public List<OrderHistoryEntryDto> getOrders(Long accountId, String status, Instant from, Instant to) {
        return orderMapper.findByAccountAndFilters(accountId, status, from, to);
    }
}
