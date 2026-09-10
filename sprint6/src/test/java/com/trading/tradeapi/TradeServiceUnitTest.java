package com.trading.tradeapi;

import com.trading.domain.account.AccountStatus;
import com.trading.domain.exception.AccountNotActiveException;
import com.trading.domain.exception.AccountNotFoundException;
import com.trading.domain.exception.DuplicateOrderException;
import com.trading.domain.exception.InstrumentNotFoundException;
import com.trading.domain.exception.InsufficientFundsException;
import com.trading.domain.exception.InsufficientHoldingsException;
import com.trading.domain.order.OrderSide;
import com.trading.domain.order.OrderStatus;
import com.trading.tradeapi.dto.OrderResponseDto;
import com.trading.tradeapi.entity.AccountRecord;
import com.trading.tradeapi.entity.HoldingRecord;
import com.trading.tradeapi.entity.InstrumentRecord;
import com.trading.tradeapi.entity.OrderRecord;
import com.trading.tradeapi.exception.OrderNotFoundException;
import com.trading.tradeapi.mapper.AccountMapper;
import com.trading.tradeapi.mapper.HoldingMapper;
import com.trading.tradeapi.mapper.InstrumentMapper;
import com.trading.tradeapi.mapper.OrderMapper;
import com.trading.tradeapi.service.TradeService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
public class TradeServiceUnitTest {

    @Mock
    private AccountMapper accountMapper;

    @Mock
    private InstrumentMapper instrumentMapper;

    @Mock
    private OrderMapper orderMapper;

    @Mock
    private HoldingMapper holdingMapper;

    @InjectMocks
    private TradeService tradeService;

    @Test
    public void placeBuyOrderSuccess() {
        AccountRecord acc = new AccountRecord(1L, "ACC-001", 100L, "Client 1", "USD", new BigDecimal("1000.00"), AccountStatus.ACTIVE, 1L, null);
        InstrumentRecord inst = new InstrumentRecord(10L, "ACME", "ACME.US", "EQUITY", "USD", "ACTIVE", null);

        when(accountMapper.findById(1L)).thenReturn(acc);
        when(instrumentMapper.findBySymbol("ACME")).thenReturn(inst);
        when(orderMapper.findByIdempotencyKey("idemp-key-12345")).thenReturn(null);
        when(accountMapper.updateCashBalanceAndVersion(eq(1L), any(), eq(1L))).thenReturn(1);

        OrderResponseDto resp = tradeService.placeOrder(1L, "ACME", OrderSide.BUY, 10L, new BigDecimal("25.50"), "idemp-key-12345");

        assertThat(resp.orderId()).isEqualTo("ORD-idemp-key-12345");
        assertThat(resp.status()).isEqualTo(OrderStatus.FILLED);
        verify(orderMapper).insertOrder(any());
    }

    @Test
    public void placeBuyOrderInsufficientFundsThrowsOrd400() {
        AccountRecord acc = new AccountRecord(1L, "ACC-001", 100L, "Client 1", "USD", new BigDecimal("10.00"), AccountStatus.ACTIVE, 1L, null);
        InstrumentRecord inst = new InstrumentRecord(10L, "ACME", "ACME.US", "EQUITY", "USD", "ACTIVE", null);

        when(accountMapper.findById(1L)).thenReturn(acc);
        when(instrumentMapper.findBySymbol("ACME")).thenReturn(inst);

        assertThatThrownBy(() -> tradeService.placeOrder(1L, "ACME", OrderSide.BUY, 100L, new BigDecimal("25.50"), "idemp-key-12345"))
                .isInstanceOf(InsufficientFundsException.class);
    }

    @Test
    public void placeSellOrderInsufficientHoldingsThrowsOrd409() {
        AccountRecord acc = new AccountRecord(1L, "ACC-001", 100L, "Client 1", "USD", new BigDecimal("1000.00"), AccountStatus.ACTIVE, 1L, null);
        InstrumentRecord inst = new InstrumentRecord(10L, "ACME", "ACME.US", "EQUITY", "USD", "ACTIVE", null);

        when(accountMapper.findById(1L)).thenReturn(acc);
        when(instrumentMapper.findBySymbol("ACME")).thenReturn(inst);
        when(holdingMapper.findByAccountAndTicker(1L, "ACME.US")).thenReturn(null); // No holdings

        assertThatThrownBy(() -> tradeService.placeOrder(1L, "ACME", OrderSide.SELL, 10L, new BigDecimal("25.50"), "idemp-key-12345"))
                .isInstanceOf(InsufficientHoldingsException.class);
    }

    @Test
    public void placeOrderAccountNotFoundThrowsAcc404() {
        when(accountMapper.findById(999L)).thenReturn(null);

        assertThatThrownBy(() -> tradeService.placeOrder(999L, "ACME", OrderSide.BUY, 10L, new BigDecimal("25.50"), "idemp-key-12345"))
                .isInstanceOf(AccountNotFoundException.class);
    }

    @Test
    public void placeOrderAccountSuspendedThrowsAcc403() {
        AccountRecord acc = new AccountRecord(1L, "ACC-001", 100L, "Client 1", "USD", new BigDecimal("1000.00"), AccountStatus.SUSPENDED, 1L, null);
        when(accountMapper.findById(1L)).thenReturn(acc);

        assertThatThrownBy(() -> tradeService.placeOrder(1L, "ACME", OrderSide.BUY, 10L, new BigDecimal("25.50"), "idemp-key-12345"))
                .isInstanceOf(AccountNotActiveException.class);
    }

    @Test
    public void placeOrderInstrumentDelistedThrowsIns404() {
        AccountRecord acc = new AccountRecord(1L, "ACC-001", 100L, "Client 1", "USD", new BigDecimal("1000.00"), AccountStatus.ACTIVE, 1L, null);
        InstrumentRecord inst = new InstrumentRecord(10L, "ACME", "ACME.US", "EQUITY", "USD", "DELISTED", Instant.now());

        when(accountMapper.findById(1L)).thenReturn(acc);
        when(instrumentMapper.findBySymbol("ACME")).thenReturn(inst);

        assertThatThrownBy(() -> tradeService.placeOrder(1L, "ACME", OrderSide.BUY, 10L, new BigDecimal("25.50"), "idemp-key-12345"))
                .isInstanceOf(InstrumentNotFoundException.class);
    }

    @Test
    public void placeOrderOptimisticLockingFailureThrowsOrd409() {
        AccountRecord acc = new AccountRecord(1L, "ACC-001", 100L, "Client 1", "USD", new BigDecimal("1000.00"), AccountStatus.ACTIVE, 1L, null);
        InstrumentRecord inst = new InstrumentRecord(10L, "ACME", "ACME.US", "EQUITY", "USD", "ACTIVE", null);

        when(accountMapper.findById(1L)).thenReturn(acc);
        when(instrumentMapper.findBySymbol("ACME")).thenReturn(inst);
        when(accountMapper.updateCashBalanceAndVersion(eq(1L), any(), eq(1L))).thenReturn(0); // 0 rows updated

        assertThatThrownBy(() -> tradeService.placeOrder(1L, "ACME", OrderSide.BUY, 10L, new BigDecimal("25.50"), "idemp-key-12345"))
                .isInstanceOf(DuplicateOrderException.class);
    }

    @Test
    public void cancelOrderNotFoundThrowsOrd409() {
        when(orderMapper.findById("non-existing-id")).thenReturn(null);

        assertThatThrownBy(() -> tradeService.cancelOrder("non-existing-id"))
                .isInstanceOf(OrderNotFoundException.class);
    }
}
