package com.trading.domain.account;

import com.trading.domain.exception.AccountNotActiveException;
import com.trading.domain.exception.AccountNotFoundException;

import java.util.Map;
import java.util.Objects;

public class AccountManager {
    private final Map<Long, Account> accounts;

    public AccountManager(Map<Long, Account> accounts) {
        this.accounts = Objects.requireNonNull(accounts);
    }

    public Account isAccountValid(long accountId) {
        Account account = isAccountFound(accountId);
        isAccountActive(account);
        return account;
    }

    public Account isAccountFound(long accountId) {
        Account account = accounts.get(accountId);

        if (account == null) {
            throw new AccountNotFoundException();
        }

        return account;
    }

    public void isAccountActive(Account account) {
        if (account.getAccountStatus() != AccountStatus.ACTIVE) {
            throw new AccountNotActiveException();
        }
    }
}
