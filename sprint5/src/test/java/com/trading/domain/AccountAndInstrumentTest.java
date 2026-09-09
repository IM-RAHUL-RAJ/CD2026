package com.trading.domain;

import com.trading.domain.account.Account;
import com.trading.domain.account.AccountManager;
import com.trading.domain.account.AccountStatus;
import com.trading.domain.exception.AccountNotActiveException;
import com.trading.domain.exception.AccountNotFoundException;
import com.trading.domain.exception.InsufficientFundsException;
import com.trading.domain.exception.InsufficientInstrumentQuantityException;
import com.trading.domain.instrument.Instrument;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AccountAndInstrumentTest {

    @Test
    void updatesAccountBalance() {
        Account account = account(new BigDecimal("100.00"), AccountStatus.ACTIVE);

        assertEquals(1L, account.getAccountId());
        assertEquals("ACC-1", account.getAccountReference());
        assertEquals(AccountStatus.ACTIVE, account.getAccountStatus());
        assertTrue(account.canAfford(new BigDecimal("100.00")));
        account.debit(new BigDecimal("25.50"));
        account.credit(new BigDecimal("10.25"));

        assertEquals(new BigDecimal("84.75"), account.getAccountBalance());
        account.setAccountStatus(AccountStatus.SUSPENDED);
        assertEquals(AccountStatus.SUSPENDED, account.getAccountStatus());
        assertThrows(NullPointerException.class, () -> account.setAccountStatus(null));
    }

    @Test
    void rejectsInvalidAccountValues() {
        assertAll(
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new Account(0, "ACC", BigDecimal.ZERO, AccountStatus.ACTIVE)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new Account(1, " ", BigDecimal.ZERO, AccountStatus.ACTIVE)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new Account(1, "ACC", new BigDecimal("1.001"), AccountStatus.ACTIVE)),
                () -> assertThrows(NullPointerException.class,
                        () -> new Account(1, "ACC", BigDecimal.ZERO, null))
        );

        Account account = account(new BigDecimal("10.00"), AccountStatus.ACTIVE);
        assertAll(
                () -> assertThrows(IllegalArgumentException.class,
                        () -> account.debit(new BigDecimal("-1.00"))),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> account.credit(new BigDecimal("-1.00"))),
                () -> assertThrows(InsufficientFundsException.class,
                        () -> account.debit(new BigDecimal("10.01"))),
                () -> assertThrows(NullPointerException.class, () -> account.canAfford(null))
        );
        assertEquals(new BigDecimal("10.00"), account.getAccountBalance());
    }

    @Test
    void validatesAccountStatus() {
        Account active = account(BigDecimal.TEN, AccountStatus.ACTIVE);
        AccountManager manager = new AccountManager(Map.of(1L, active));

        assertSame(active, manager.isAccountValid(1L));
        assertThrows(AccountNotFoundException.class, () -> manager.isAccountFound(2L));
        assertThrows(AccountNotActiveException.class,
                () -> manager.isAccountActive(account(BigDecimal.TEN, AccountStatus.CLOSED)));
        assertThrows(NullPointerException.class, () -> new AccountManager(null));
    }

    @Test
    void updatesInstrumentQuantityAndPrice() {
        Instrument instrument = instrument(3);
        assertAll(
                () -> assertEquals(1L, instrument.getInstrumentId()),
                () -> assertEquals("AAPL", instrument.getSymbol()),
                () -> assertEquals("STOCK", instrument.getAssetClass()),
                () -> assertEquals("USD", instrument.getCurrency()),
                () -> assertTrue(instrument.isTradeable()),
                () -> assertEquals(new BigDecimal("10.00"), instrument.getPrice())
        );
        instrument.setTradeable(false);
        assertFalse(instrument.isTradeable());
        instrument.setTradeable(true);
        assertTrue(instrument.hasQuantity(3));
        assertFalse(instrument.hasQuantity(0));
        instrument.reserveQuantity(2);

        assertEquals(1, instrument.getQuantityAvailable());
        assertThrows(InsufficientInstrumentQuantityException.class, () -> instrument.reserveQuantity(2));
        assertEquals(1, instrument.getQuantityAvailable());
        assertThrows(IllegalArgumentException.class, () -> instrument.setPrice(BigDecimal.ZERO));
        assertThrows(IllegalArgumentException.class, () -> instrument.setPrice(new BigDecimal("1.001")));
        instrument.setPrice(new BigDecimal("12.50"));
        assertEquals(new BigDecimal("12.50"), instrument.getPrice());
    }

    @Test
    void rejectsInvalidInstrumentValues() {
        assertAll(
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new Instrument(0, "AAPL", "STOCK", "USD", true, BigDecimal.ONE, 1)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new Instrument(1, " ", "STOCK", "USD", true, BigDecimal.ONE, 1)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new Instrument(1, "AAPL", "", "USD", true, BigDecimal.ONE, 1)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new Instrument(1, "AAPL", "STOCK", "", true, BigDecimal.ONE, 1)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new Instrument(1, "AAPL", "STOCK", "USD", true, BigDecimal.ZERO, 1)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new Instrument(1, "AAPL", "STOCK", "USD", true, BigDecimal.ONE, -1))
        );
    }

    private Account account(BigDecimal balance, AccountStatus status) {
        return new Account(1L, "ACC-1", balance, status);
    }

    private Instrument instrument(long quantity) {
        return new Instrument(1L, "AAPL", "STOCK", "USD", true,
                new BigDecimal("10.00"), quantity);
    }
}
