package com.trading.tradeapi.service;

import com.trading.tradeapi.dto.AccountResponseDto;
import com.trading.tradeapi.dto.BalanceResponseDto;
import com.trading.tradeapi.dto.CreateAccountRequestDto;
import com.trading.tradeapi.dto.OrderHistoryEntryDto;
import com.trading.tradeapi.dto.PositionResponseDto;
import com.trading.tradeapi.entity.AccountRecord;
import com.trading.tradeapi.enums.AccountStatus;
import com.trading.tradeapi.exception.AccountNotFoundException;
import com.trading.tradeapi.exception.AccountNotActiveException;
import com.trading.tradeapi.mapper.AccountMapper;
import com.trading.tradeapi.mapper.UserMapper;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

@Service
public class AccountService {

    private static final BigDecimal DEFAULT_BALANCE = new BigDecimal("100000.00");

    private final AccountMapper accountMapper;
    private final HoldingService holdingService;
    private final OrderService orderService;
    private final UserMapper userMapper;

    public AccountService(AccountMapper accountMapper,
                          HoldingService holdingService,
                          OrderService orderService,
                          UserMapper userMapper) {
        this.accountMapper = accountMapper;
        this.holdingService = holdingService;
        this.orderService = orderService;
        this.userMapper = userMapper;
    }

    private AccountRecord validateAccountExists(Long id) {
        AccountRecord acc = accountMapper.findById(id);
        if (acc == null) {
            throw new AccountNotFoundException();
        }
        return acc;
    }

    public AccountRecord getAccountRecord(Long id) {
        return validateAccountExists(id);
    }

    public void validateAccountActive(AccountRecord account) {
        if (account.getStatus() != AccountStatus.ACTIVE) {
            throw new AccountNotActiveException();
        }
    }

    @Transactional(readOnly = true)
    public AccountResponseDto getAccount(Long id) {
        AccountRecord acc = validateAccountExists(id);
        return new AccountResponseDto(
                acc.getAccountId(),
                formatAccountId(acc.getAccountId()),
                acc.getHolderName(),
                acc.getCashBalance(),
                acc.getStatus(),
                acc.getVersion(),
                Instant.now()
        );
    }

    @Transactional(readOnly = true)
    public BalanceResponseDto getBalance(Long id) {
        AccountRecord acc = validateAccountExists(id);
        return new BalanceResponseDto(
                acc.getAccountId(),
                acc.getCashBalance(),
                acc.getCurrency(),
                Instant.now()
        );
    }

    @Transactional(readOnly = true)
    public List<PositionResponseDto> getHoldings(Long id) {
        validateAccountExists(id);
        return holdingService.getHoldingsByAccountId(id);
    }

    @Transactional(readOnly = true)
    public List<OrderHistoryEntryDto> getOrders(Long id, String status, Instant from, Instant to) {
        validateAccountExists(id);
        return orderService.getOrders(id, status, from, to);
    }

    @Transactional
    public AccountResponseDto createAccount(CreateAccountRequestDto request) {
        if (request.userId() == null) {
            throw new IllegalArgumentException("userId is required");
        }
        var user = userMapper.findById(request.userId());
        if (user == null) {
            throw new IllegalArgumentException("User not found");
        }
        BigDecimal initialBalance = request.initialBalance() != null ? request.initialBalance() : DEFAULT_BALANCE;
        if (initialBalance.compareTo(DEFAULT_BALANCE) < 0) {
            throw new IllegalArgumentException("Initial balance must be at least 100000");
        }
        String currency = request.currency() != null && !request.currency().isBlank()
                ? request.currency()
                : "USD";
        String holderName = (user.getFirstName() + " " + user.getLastName()).trim();
        AccountRecord newAccount = new AccountRecord(
                null,
                request.userId(),
                holderName,
                currency,
                initialBalance,
                AccountStatus.ACTIVE,
                0L,
                null
        );
        accountMapper.insertAccount(newAccount);
        return getAccount(newAccount.getAccountId());
    }

    @Transactional
    public void deleteAccount(Long accountId) {
        validateAccountExists(accountId);
        accountMapper.deleteAccount(accountId);
    }

    private String formatAccountId(Long accountId) {
        return "ACC-" + String.format("%06d", accountId);
    }
}
