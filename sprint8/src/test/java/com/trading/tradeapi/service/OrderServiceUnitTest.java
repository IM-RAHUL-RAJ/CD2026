package com.trading.tradeapi.service;

import com.trading.tradeapi.dto.OrderHistoryEntryDto;
import com.trading.tradeapi.dto.OrderResponseDto;
import com.trading.tradeapi.dto.PlaceOrderRequestDto;
import com.trading.tradeapi.entity.AccountRecord;
import com.trading.tradeapi.entity.OrderRecord;
import com.trading.tradeapi.enums.AccountStatus;
import com.trading.tradeapi.enums.OrderSide;
import com.trading.tradeapi.enums.OrderStatus;
import com.trading.tradeapi.enums.OrderType;
import com.trading.tradeapi.exception.AccountNotActiveException;
import com.trading.tradeapi.exception.DuplicateOrderException;
import com.trading.tradeapi.exception.OrderNotFoundException;
import com.trading.tradeapi.kafka.producer.OrderProducer;
import com.trading.tradeapi.mapper.AccountMapper;
import com.trading.tradeapi.mapper.HoldingMapper;
import com.trading.tradeapi.mapper.OrderMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class OrderServiceUnitTest {

    @Mock
    private InstrumentService instrumentService;
    @Mock
    private AccountMapper accountMapper;
    @Mock
    private OrderMapper orderMapper;
    @Mock
    private HoldingMapper holdingMapper;
    @Mock
    private AccountService accountService;
    @Mock
    private OrderProducer orderProducer;

    @InjectMocks
    private OrderService orderService;

    private PlaceOrderRequestDto sampleRequest;
    private AccountRecord activeAccount;

    @BeforeEach
    public void setup() {
        sampleRequest = new PlaceOrderRequestDto(
                101L,
                "AAPL",
                OrderSide.BUY,
                10L,
                new BigDecimal("150.00"),
                OrderType.LIMIT,
                "idempotency-key-123"
        );

        activeAccount = new AccountRecord(
                101L,
                1L,
                "Test Holder",
                "USD",
                new BigDecimal("10000.00"),
                AccountStatus.ACTIVE,
                0L,
                null
        );
    }

    @Test
    @DisplayName("placeOrder: Successful order placement returns NEW status and publishes to Kafka")
    public void placeOrderSuccess() {
        when(orderMapper.findByIdempotencyKey(sampleRequest.idempotencyKey())).thenReturn(null);
        when(accountService.getAccountRecord(101L)).thenReturn(activeAccount);

        doAnswer(invocation -> {
            OrderRecord record = invocation.getArgument(0);
            record.setOrderId(1L);
            return 1;
        }).when(orderMapper).insertOrder(any(OrderRecord.class));

        OrderResponseDto response = orderService.placeOrder(sampleRequest);

        assertNotNull(response);
        assertEquals("1", response.orderId());
        assertEquals(OrderStatus.NEW, response.status());
        assertEquals("AAPL", response.symbol());
        assertEquals(OrderSide.BUY, response.side());

        verify(orderProducer, times(1)).publishOrderPlaced(
                eq(1L), eq(101L), eq("AAPL"), eq("BUY"),
                eq(10L), eq(new BigDecimal("150.00")), eq("idempotency-key-123"), any()
        );
    }

    @Test
    @DisplayName("placeOrder: Duplicate idempotency key throws DuplicateOrderException")
    public void placeOrderDuplicateIdempotencyKey() {
        OrderRecord existing = new OrderRecord();
        when(orderMapper.findByIdempotencyKey(sampleRequest.idempotencyKey())).thenReturn(existing);

        assertThrows(DuplicateOrderException.class, () -> orderService.placeOrder(sampleRequest));
        verify(orderMapper, never()).insertOrder(any());
    }

    @Test
    @DisplayName("placeOrder: Inactive account throws AccountNotActiveException")
    public void placeOrderInactiveAccount() {
        when(orderMapper.findByIdempotencyKey(sampleRequest.idempotencyKey())).thenReturn(null);
        when(accountService.getAccountRecord(101L)).thenReturn(activeAccount);
        doThrow(new AccountNotActiveException()).when(accountService).validateAccountActive(activeAccount);

        assertThrows(AccountNotActiveException.class, () -> orderService.placeOrder(sampleRequest));
        verify(orderMapper, never()).insertOrder(any());
    }

    @Test
    @DisplayName("placeOrder: DB insert failure throws RuntimeException")
    public void placeOrderDbInsertFailure() {
        when(orderMapper.findByIdempotencyKey(sampleRequest.idempotencyKey())).thenReturn(null);
        when(accountService.getAccountRecord(101L)).thenReturn(activeAccount);
        when(orderMapper.insertOrder(any(OrderRecord.class))).thenReturn(0);

        assertThrows(RuntimeException.class, () -> orderService.placeOrder(sampleRequest));
    }

    @Test
    @DisplayName("placeOrder: Kafka publish error is caught cleanly without failing order save")
    public void placeOrderKafkaErrorHandled() {
        when(orderMapper.findByIdempotencyKey(sampleRequest.idempotencyKey())).thenReturn(null);
        when(accountService.getAccountRecord(101L)).thenReturn(activeAccount);
        doAnswer(inv -> {
            OrderRecord record = inv.getArgument(0);
            record.setOrderId(1L);
            return 1;
        }).when(orderMapper).insertOrder(any(OrderRecord.class));

        doThrow(new RuntimeException("Kafka down")).when(orderProducer).publishOrderPlaced(
                any(), any(), any(), any(), any(), any(), any(), any()
        );

        OrderResponseDto response = orderService.placeOrder(sampleRequest);
        assertNotNull(response);
        assertEquals("1", response.orderId());
    }

    @Test
    @DisplayName("cancelOrder: Successfully cancels NEW order")
    public void cancelOrderSuccess() {
        OrderRecord existing = new OrderRecord();
        existing.setStatus(OrderStatus.NEW);
        existing.setTicker("AAPL");
        existing.setSide(OrderSide.BUY);
        existing.setQuantity(new BigDecimal("10"));
        existing.setPrice(new BigDecimal("150.00"));

        when(orderMapper.findById("1")).thenReturn(existing);
        when(orderMapper.cancelOrder("1")).thenReturn(1);

        OrderResponseDto response = orderService.cancelOrder("1");
        assertNotNull(response);
        assertEquals(OrderStatus.CANCELLED, response.status());
    }

    @Test
    @DisplayName("cancelOrder: Order not found throws OrderNotFoundException")
    public void cancelOrderNotFound() {
        when(orderMapper.findById("99")).thenReturn(null);
        assertThrows(OrderNotFoundException.class, () -> orderService.cancelOrder("99"));
    }

    @Test
    @DisplayName("cancelOrder: Order not in NEW state throws DuplicateOrderException")
    public void cancelOrderNotNew() {
        OrderRecord filled = new OrderRecord();
        filled.setStatus(OrderStatus.FILLED);
        when(orderMapper.findById("1")).thenReturn(filled);

        assertThrows(DuplicateOrderException.class, () -> orderService.cancelOrder("1"));
    }

    @Test
    @DisplayName("cancelOrder: DB update 0 rows throws DuplicateOrderException")
    public void cancelOrderDbUpdateZero() {
        OrderRecord existing = new OrderRecord();
        existing.setStatus(OrderStatus.NEW);
        when(orderMapper.findById("1")).thenReturn(existing);
        when(orderMapper.cancelOrder("1")).thenReturn(0);

        assertThrows(DuplicateOrderException.class, () -> orderService.cancelOrder("1"));
    }

    @Test
    @DisplayName("getOrders: Returns list of orders for account")
    public void getOrdersSuccess() {
        OrderHistoryEntryDto entry = new OrderHistoryEntryDto(
                "1", 101L, "AAPL", OrderSide.BUY, 10L, new BigDecimal("150.00"),
                null, OrderStatus.NEW, null, Instant.now()
        );
        when(orderMapper.findByAccountAndFilters(101L, "NEW", null, null)).thenReturn(List.of(entry));

        List<OrderHistoryEntryDto> result = orderService.getOrders(101L, "NEW", null, null);
        assertEquals(1, result.size());
        assertEquals("AAPL", result.get(0).symbol());
    }
}
