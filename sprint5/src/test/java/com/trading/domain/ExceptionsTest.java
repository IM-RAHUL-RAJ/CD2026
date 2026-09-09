package com.trading.domain;

import com.trading.domain.exception.*;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ExceptionsTest {

    @Test
    void accountNotFoundHasCorrectCodeAndMessage() {

        AccountNotFoundException exception =
                new AccountNotFoundException();

        assertEquals(
                "ACC-404",
                exception.getCode()
        );

        assertEquals(
                "Account not found",
                exception.getMessage()
        );
    }

    @Test
    void accountNotActiveHasCorrectCodeAndMessage() {

        AccountNotActiveException exception =
                new AccountNotActiveException();

        assertEquals(
                "ACC-403",
                exception.getCode()
        );

        assertEquals(
                "Account is not active",
                exception.getMessage()
        );
    }

    @Test
    void instrumentNotFoundHasCorrectCodeAndMessage() {

        InstrumentNotFoundException exception =
                new InstrumentNotFoundException();

        assertEquals(
                "INS-404",
                exception.getCode()
        );

        assertEquals(
                "Instrument not found or not tradable",
                exception.getMessage()
        );
    }

    @Test
    void insufficientFundsHasCorrectCodeAndMessage() {

        InsufficientFundsException exception =
                new InsufficientFundsException();

        assertEquals(
                "ORD-400",
                exception.getCode()
        );

        assertEquals(
                "Insufficient funds",
                exception.getMessage()
        );
    }

    @Test
    void insufficientHoldingsHasCorrectCodeAndMessage() {

        InsufficientHoldingsException exception =
                new InsufficientHoldingsException();

        assertEquals(
                "ORD-409",
                exception.getCode()
        );

        assertEquals(
                "Insufficient holdings",
                exception.getMessage()
        );
    }

    @Test
    void duplicateOrderHasCorrectCodeAndMessage() {

        DuplicateOrderException exception =
                new DuplicateOrderException();

        assertEquals(
                "ORD-409",
                exception.getCode()
        );

        assertEquals(
                "Duplicate order",
                exception.getMessage()
        );
    }

    @Test
    void remainingOrderExceptionsHaveCorrectCodesAndMessages() {
        InsufficientInstrumentQuantityException quantityException =
                new InsufficientInstrumentQuantityException();
        OrderValidationException defaultValidationException = new OrderValidationException();
        OrderValidationException customValidationException = new OrderValidationException("Invalid price");

        assertAll(
                () -> assertEquals("ORD-409", quantityException.getCode()),
                () -> assertEquals("Insufficient instrument quantity", quantityException.getMessage()),
                () -> assertEquals("VAL-422", defaultValidationException.getCode()),
                () -> assertEquals("Invalid order", defaultValidationException.getMessage()),
                () -> assertEquals("VAL-422", customValidationException.getCode()),
                () -> assertEquals("Invalid price", customValidationException.getMessage())
        );
    }

    @Test
    void exceptionsAreRuntimeExceptions() {

        assertTrue(
                new AccountNotFoundException()
                        instanceof RuntimeException
        );

        assertTrue(
                new InsufficientFundsException()
                        instanceof RuntimeException
        );
    }
}
