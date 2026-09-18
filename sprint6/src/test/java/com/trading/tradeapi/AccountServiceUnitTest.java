package com.trading.tradeapi;

import com.trading.tradeapi.dto.AccountResponseDto;
import com.trading.tradeapi.dto.BalanceResponseDto;
import com.trading.tradeapi.dto.CreateAccountRequestDto;
import com.trading.tradeapi.dto.OrderHistoryEntryDto;
import com.trading.tradeapi.dto.PositionResponseDto;
import com.trading.tradeapi.entity.AccountRecord;
import com.trading.tradeapi.enums.AccountStatus;
import com.trading.tradeapi.exception.AccountNotActiveException;
import com.trading.tradeapi.exception.AccountNotFoundException;
import com.trading.tradeapi.mapper.AccountMapper;
import com.trading.tradeapi.service.AccountService;
import com.trading.tradeapi.service.HoldingService;
import com.trading.tradeapi.service.OrderService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doAnswer;

@ExtendWith(MockitoExtension.class)
public class AccountServiceUnitTest {

    @Mock
    private AccountMapper accountMapper;

    @Mock
    private HoldingService holdingService;

    @Mock
    private OrderService orderService;

    private AccountService accountService;

    @BeforeEach
    public void setUp() {
        accountService = new AccountService(accountMapper, holdingService, orderService);
    }

    @Test
    public void getAccountRecordSuccessReturnsAccount() {
        AccountRecord mockAccount = createMockAccount(1L, "TestAccount", AccountStatus.ACTIVE);
        when(accountMapper.findById(1L)).thenReturn(mockAccount);

        AccountRecord result = accountService.getAccountRecord(1L);

        assertThat(result).isNotNull();
        assertThat(result.getAccountId()).isEqualTo(1L);
        assertThat(result.getAccountName()).isEqualTo("TestAccount");
        assertThat(result.getStatus()).isEqualTo(AccountStatus.ACTIVE);
        verify(accountMapper).findById(1L);
    }

    @Test
    public void getAccountRecordNotFoundThrowsException() {
        when(accountMapper.findById(999L)).thenReturn(null);

        assertThatThrownBy(() -> accountService.getAccountRecord(999L))
                .isInstanceOf(AccountNotFoundException.class)
                .hasMessage("Account not found");
    }

    @Test
    public void validateAccountActiveSuccessWhenActive() {
        AccountRecord account = createMockAccount(1L, "TestAccount", AccountStatus.ACTIVE);

        // Should not throw
        accountService.validateAccountActive(account);
    }

    @Test
    public void validateAccountActiveThrowsExceptionWhenSuspended() {
        AccountRecord account = createMockAccount(1L, "TestAccount", AccountStatus.SUSPENDED);

        assertThatThrownBy(() -> accountService.validateAccountActive(account))
                .isInstanceOf(AccountNotActiveException.class)
                .hasMessage("Account is not active");
    }

    @Test
    public void validateAccountActiveThrowsExceptionWhenClosed() {
        AccountRecord account = createMockAccount(1L, "TestAccount", AccountStatus.CLOSED);

        assertThatThrownBy(() -> accountService.validateAccountActive(account))
                .isInstanceOf(AccountNotActiveException.class);
    }

    @Test
    public void getAccountSuccessReturnsAccountDto() {
        AccountRecord mockAccount = createMockAccount(1L, "TestAccount", AccountStatus.ACTIVE);
        mockAccount.setClientName("Test Client");
        mockAccount.setCashBalance(new BigDecimal("10000.00"));
        mockAccount.setVersion(0L);

        when(accountMapper.findById(1L)).thenReturn(mockAccount);

        AccountResponseDto result = accountService.getAccount(1L);

        assertThat(result).isNotNull();
        assertThat(result.id()).isEqualTo(1L);
        assertThat(result.accountId()).isEqualTo("TestAccount");
        assertThat(result.holderName()).isEqualTo("Test Client");
        assertThat(result.status()).isEqualTo(AccountStatus.ACTIVE);
    }

    @Test
    public void getAccountNotFoundThrowsException() {
        when(accountMapper.findById(999L)).thenReturn(null);

        assertThatThrownBy(() -> accountService.getAccount(999L))
                .isInstanceOf(AccountNotFoundException.class);
    }

    @Test
    public void getBalanceSuccessReturnsBalance() {
        AccountRecord mockAccount = createMockAccount(1L, "TestAccount", AccountStatus.ACTIVE);
        mockAccount.setCashBalance(new BigDecimal("5000.00"));
        mockAccount.setCurrency("USD");

        when(accountMapper.findById(1L)).thenReturn(mockAccount);

        BalanceResponseDto result = accountService.getBalance(1L);

        assertThat(result).isNotNull();
        assertThat(result.accountId()).isEqualTo(1L);
        assertThat(result.cashBalance()).isEqualTo(new BigDecimal("5000.00"));
        assertThat(result.currency()).isEqualTo("USD");
    }

    @Test
    public void getBalanceNotFoundThrowsException() {
        when(accountMapper.findById(999L)).thenReturn(null);

        assertThatThrownBy(() -> accountService.getBalance(999L))
                .isInstanceOf(AccountNotFoundException.class);
    }

    @Test
    public void getHoldingsSuccessReturnsPositions() {
        AccountRecord mockAccount = createMockAccount(1L, "TestAccount", AccountStatus.ACTIVE);
        List<PositionResponseDto> mockHoldings = List.of(
                new PositionResponseDto(1L, "AAPL", 100L, new BigDecimal("150.00"))
        );

        when(accountMapper.findById(1L)).thenReturn(mockAccount);
        when(holdingService.getHoldingsByAccountId(1L)).thenReturn(mockHoldings);

        List<PositionResponseDto> result = accountService.getHoldings(1L);

        assertThat(result).isNotNull().hasSize(1);
        assertThat(result.get(0).symbol()).isEqualTo("AAPL");
        verify(holdingService).getHoldingsByAccountId(1L);
    }

    @Test
    public void getHoldingsAccountNotFoundThrowsException() {
        when(accountMapper.findById(999L)).thenReturn(null);

        assertThatThrownBy(() -> accountService.getHoldings(999L))
                .isInstanceOf(AccountNotFoundException.class);
    }

    @Test
    public void getOrdersSuccessReturnsOrderHistory() {
        AccountRecord mockAccount = createMockAccount(1L, "TestAccount", AccountStatus.ACTIVE);
        Instant now = Instant.now();

        when(accountMapper.findById(1L)).thenReturn(mockAccount);
        when(orderService.getOrders(1L, "FILLED", now, now)).thenReturn(List.of());

        List<OrderHistoryEntryDto> result = accountService.getOrders(1L, "FILLED", now, now);

        assertThat(result).isNotNull().isEmpty();
        verify(orderService).getOrders(1L, "FILLED", now, now);
    }

    @Test
    public void getOrdersAccountNotFoundThrowsException() {
        when(accountMapper.findById(999L)).thenReturn(null);

        assertThatThrownBy(() -> accountService.getOrders(999L, null, null, null))
                .isInstanceOf(AccountNotFoundException.class);
    }

    @Test
    public void createAccountSuccessCreatesNewAccount() {
        CreateAccountRequestDto request = new CreateAccountRequestDto(
                "NewAccount",
                1L,
                "USD",
                new BigDecimal("5000.00")
        );
        AccountRecord savedAccount = createMockAccount(2L, "NewAccount", AccountStatus.ACTIVE);
        savedAccount.setClientName("Test Client");
        savedAccount.setCashBalance(new BigDecimal("5000.00"));
        savedAccount.setCurrency("USD");
        savedAccount.setVersion(0L);

        // Mock insertAccount to set the ID on the AccountRecord (simulating useGeneratedKeys)
        doAnswer(invocation -> {
            AccountRecord acc = invocation.getArgument(0);
            acc.setAccountId(2L);
            return null;
        }).when(accountMapper).insertAccount(any(AccountRecord.class));
        
        when(accountMapper.findById(2L)).thenReturn(savedAccount);

        AccountResponseDto result = accountService.createAccount(request);

        assertThat(result).isNotNull();
        assertThat(result.accountId()).isEqualTo("NewAccount");
        verify(accountMapper).insertAccount(any(AccountRecord.class));
    }

    @Test
    public void deleteAccountSuccessDeletesAccount() {
        AccountRecord mockAccount = createMockAccount(1L, "TestAccount", AccountStatus.ACTIVE);
        when(accountMapper.findById(1L)).thenReturn(mockAccount);

        accountService.deleteAccount(1L);

        verify(accountMapper).findById(1L);
        verify(accountMapper).deleteAccount(1L);
    }

    @Test
    public void deleteAccountNotFoundThrowsException() {
        when(accountMapper.findById(999L)).thenReturn(null);

        assertThatThrownBy(() -> accountService.deleteAccount(999L))
                .isInstanceOf(AccountNotFoundException.class);
    }

    private AccountRecord createMockAccount(Long accountId, String accountName, AccountStatus status) {
        return new AccountRecord(
                accountId,
                accountName,
                1L,
                "Test Client",
                "USD",
                new BigDecimal("10000.00"),
                status,
                0L,
                null
        );
    }
}
