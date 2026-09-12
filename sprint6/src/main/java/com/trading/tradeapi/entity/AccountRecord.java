package com.trading.tradeapi.entity;

import com.trading.tradeapi.enums.AccountStatus;

import java.math.BigDecimal;
import java.time.Instant;

public class AccountRecord {
    private Long accountId;
    private String accountName;
    private Long clientId;
    private String clientName;
    private String currency;
    private BigDecimal cashBalance;
    private AccountStatus status;
    private Long version;
    private Instant closedAt;

    public AccountRecord() {}

    public AccountRecord(Long accountId, String accountName, Long clientId, String clientName,
                         String currency, BigDecimal cashBalance, AccountStatus status,
                         Long version, Instant closedAt) {
        this.accountId = accountId;
        this.accountName = accountName;
        this.clientId = clientId;
        this.clientName = clientName;
        this.currency = currency;
        this.cashBalance = cashBalance;
        this.status = status;
        this.version = version;
        this.closedAt = closedAt;
    }

    public Long getAccountId() { return accountId; }
    public void setAccountId(Long accountId) { this.accountId = accountId; }

    public String getAccountName() { return accountName; }
    public void setAccountName(String accountName) { this.accountName = accountName; }

    public Long getClientId() { return clientId; }
    public void setClientId(Long clientId) { this.clientId = clientId; }

    public String getClientName() { return clientName; }
    public void setClientName(String clientName) { this.clientName = clientName; }

    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }

    public BigDecimal getCashBalance() { return cashBalance; }
    public void setCashBalance(BigDecimal cashBalance) { this.cashBalance = cashBalance; }

    public AccountStatus getStatus() { return status; }
    public void setStatus(AccountStatus status) { this.status = status; }

    public Long getVersion() { return version; }
    public void setVersion(Long version) { this.version = version; }

    public Instant getClosedAt() { return closedAt; }
    public void setClosedAt(Instant closedAt) { this.closedAt = closedAt; }
}
