package com.trading.domain;

import com.trading.domain.dto.PlaceOrderRequest;
import com.trading.domain.order.OrderSide;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("PlaceOrderRequest DTO Validation Unit Tests")
class PlaceOrderRequestValidationTest {

    private static Validator validator;

    @BeforeAll
    static void setUpValidator() {
        ValidatorFactory factory =
                Validation.buildDefaultValidatorFactory();

        validator = factory.getValidator();
    }

    private PlaceOrderRequest createValidRequest() {
        return new PlaceOrderRequest(
                1001L,
                "AAPL",
                OrderSide.BUY,
                10L,
                new BigDecimal("150.25"),
                "KEY-12345678"
        );
    }

    @Test
    @DisplayName("Valid request passes validation with zero violations")
    void testValidPlaceOrderRequestPassesValidation() {

        PlaceOrderRequest request = createValidRequest();

        Set<ConstraintViolation<PlaceOrderRequest>> violations =
                validator.validate(request);

        assertTrue(
                violations.isEmpty(),
                "Valid request should produce zero constraint violations"
        );
    }

    @Test
    @DisplayName("Null required fields are rejected")
    void testNullRequiredFieldsFailsValidation() {

        PlaceOrderRequest request =
                new PlaceOrderRequest(
                        null,
                        null,
                        null,
                        null,
                        null,
                        null
                );

        Set<ConstraintViolation<PlaceOrderRequest>> violations =
                validator.validate(request);

        assertFalse(
                violations.isEmpty(),
                "Null required fields should fail validation"
        );

        Set<String> invalidProperties =
                violations.stream()
                        .map(v -> v.getPropertyPath().toString())
                        .collect(Collectors.toSet());

        assertTrue(invalidProperties.contains("accountId"));
        assertTrue(invalidProperties.contains("symbol"));
        assertTrue(invalidProperties.contains("side"));
        assertTrue(invalidProperties.contains("quantity"));
        assertTrue(invalidProperties.contains("price"));
        assertTrue(invalidProperties.contains("idempotencyKey"));
    }

    @Test
    @DisplayName("accountId constraint (numeric account key, at least 1)")
    void testAccountIdBoundaryValidation() {

        PlaceOrderRequest invalidZero =
                new PlaceOrderRequest(
                        0L,
                        "AAPL",
                        OrderSide.BUY,
                        10L,
                        new BigDecimal("150.25"),
                        "KEY-12345678"
                );

        Set<ConstraintViolation<PlaceOrderRequest>> zeroViolations =
                validator.validate(invalidZero);

        assertFalse(
                zeroViolations.isEmpty(),
                "accountId = 0 should be rejected"
        );

        PlaceOrderRequest invalidNegative =
                new PlaceOrderRequest(
                        -5L,
                        "AAPL",
                        OrderSide.BUY,
                        10L,
                        new BigDecimal("150.25"),
                        "KEY-12345678"
                );

        Set<ConstraintViolation<PlaceOrderRequest>> negativeViolations =
                validator.validate(invalidNegative);

        assertFalse(
                negativeViolations.isEmpty(),
                "accountId < 0 should be rejected"
        );

        PlaceOrderRequest validBoundary =
                new PlaceOrderRequest(
                        1L,
                        "AAPL",
                        OrderSide.BUY,
                        10L,
                        new BigDecimal("150.25"),
                        "KEY-12345678"
                );

        Set<ConstraintViolation<PlaceOrderRequest>> validViolations =
                validator.validate(validBoundary);

        assertTrue(
                validViolations.isEmpty(),
                "accountId = 1 should be accepted"
        );
    }

    @Test
    @DisplayName("symbol constraint (not blank, at most 20 characters)")
    void testSymbolNotBlankAndMaxLengthValidation() {

        PlaceOrderRequest blankSymbol =
                new PlaceOrderRequest(
                        1001L,
                        "   ",
                        OrderSide.BUY,
                        10L,
                        new BigDecimal("150.25"),
                        "KEY-12345678"
                );

        Set<ConstraintViolation<PlaceOrderRequest>> blankViolations =
                validator.validate(blankSymbol);

        assertFalse(
                blankViolations.isEmpty(),
                "Blank symbol should be rejected"
        );

        PlaceOrderRequest longSymbol =
                new PlaceOrderRequest(
                        1001L,
                        "A".repeat(21),
                        OrderSide.BUY,
                        10L,
                        new BigDecimal("150.25"),
                        "KEY-12345678"
                );

        Set<ConstraintViolation<PlaceOrderRequest>> longViolations =
                validator.validate(longSymbol);

        assertFalse(
                longViolations.isEmpty(),
                "Symbol over 20 characters should be rejected"
        );

        PlaceOrderRequest exactSymbol =
                new PlaceOrderRequest(
                        1001L,
                        "A".repeat(20),
                        OrderSide.BUY,
                        10L,
                        new BigDecimal("150.25"),
                        "KEY-12345678"
                );

        Set<ConstraintViolation<PlaceOrderRequest>> exactViolations =
                validator.validate(exactSymbol);

        assertTrue(
                exactViolations.isEmpty(),
                "Symbol of exactly 20 characters should be accepted"
        );
    }

    @Test
    @DisplayName("side (required, one of BUY or SELL)")
    void testSideRequiredValidation() {

        PlaceOrderRequest request =
                new PlaceOrderRequest(
                        1001L,
                        "AAPL",
                        null,
                        10L,
                        new BigDecimal("150.25"),
                        "KEY-12345678"
                );

        Set<ConstraintViolation<PlaceOrderRequest>> violations =
                validator.validate(request);

        assertFalse(
                violations.isEmpty(),
                "Null side should be rejected"
        );
    }

    @Test
    @DisplayName("quantity constraint (whole units, greater than zero)")
    void testQuantityMustBeWholeUnitGreaterThanZeroValidation() {

        PlaceOrderRequest zeroQuantity =
                new PlaceOrderRequest(
                        1001L,
                        "AAPL",
                        OrderSide.BUY,
                        0L,
                        new BigDecimal("150.25"),
                        "KEY-12345678"
                );

        Set<ConstraintViolation<PlaceOrderRequest>> zeroViolations =
                validator.validate(zeroQuantity);

        assertFalse(
                zeroViolations.isEmpty(),
                "quantity = 0 should be rejected"
        );

        PlaceOrderRequest negativeQuantity =
                new PlaceOrderRequest(
                        1001L,
                        "AAPL",
                        OrderSide.BUY,
                        -10L,
                        new BigDecimal("150.25"),
                        "KEY-12345678"
                );

        Set<ConstraintViolation<PlaceOrderRequest>> negativeViolations =
                validator.validate(negativeQuantity);

        assertFalse(
                negativeViolations.isEmpty(),
                "quantity < 0 should be rejected"
        );

        PlaceOrderRequest minimumQuantity =
                new PlaceOrderRequest(
                        1001L,
                        "AAPL",
                        OrderSide.BUY,
                        1L,
                        new BigDecimal("150.25"),
                        "KEY-12345678"
                );

        Set<ConstraintViolation<PlaceOrderRequest>> minimumViolations =
                validator.validate(minimumQuantity);

        assertTrue(
                minimumViolations.isEmpty(),
                "quantity = 1 should be accepted"
        );
    }

    @Test
    @DisplayName("price constraint (greater than zero, at most 2 decimal places)")
    void testPriceMustBeGreaterThanZeroAndTwoDecimalPlacesValidation() {

        PlaceOrderRequest zeroPrice =
                new PlaceOrderRequest(
                        1001L,
                        "AAPL",
                        OrderSide.BUY,
                        10L,
                        new BigDecimal("0.00"),
                        "KEY-12345678"
                );

        Set<ConstraintViolation<PlaceOrderRequest>> zeroViolations =
                validator.validate(zeroPrice);

        assertFalse(
                zeroViolations.isEmpty(),
                "price = 0.00 should be rejected"
        );

        PlaceOrderRequest threeDecimals =
                new PlaceOrderRequest(
                        1001L,
                        "AAPL",
                        OrderSide.BUY,
                        10L,
                        new BigDecimal("10.555"),
                        "KEY-12345678"
                );

        Set<ConstraintViolation<PlaceOrderRequest>> decimalViolations =
                validator.validate(threeDecimals);

        assertFalse(
                decimalViolations.isEmpty(),
                "price with 3 decimal places should be rejected"
        );

        PlaceOrderRequest validPrice =
                new PlaceOrderRequest(
                        1001L,
                        "AAPL",
                        OrderSide.BUY,
                        10L,
                        new BigDecimal("0.01"),
                        "KEY-12345678"
                );

        Set<ConstraintViolation<PlaceOrderRequest>> validViolations =
                validator.validate(validPrice);

        assertTrue(
                validViolations.isEmpty(),
                "price = 0.01 with 2 decimal places should be accepted"
        );
    }

    @Test
    @DisplayName("idempotencyKey constraint (between 8 and 100 characters)")
    void testIdempotencyKeyLengthBetween8And100CharsValidation() {

        PlaceOrderRequest shortKey =
                new PlaceOrderRequest(
                        1001L,
                        "AAPL",
                        OrderSide.BUY,
                        10L,
                        new BigDecimal("150.25"),
                        "KEY1234"
                );

        Set<ConstraintViolation<PlaceOrderRequest>> shortViolations =
                validator.validate(shortKey);

        assertFalse(
                shortViolations.isEmpty(),
                "idempotencyKey < 8 characters should be rejected"
        );

        PlaceOrderRequest longKey =
                new PlaceOrderRequest(
                        1001L,
                        "AAPL",
                        OrderSide.BUY,
                        10L,
                        new BigDecimal("150.25"),
                        "K".repeat(101)
                );

        Set<ConstraintViolation<PlaceOrderRequest>> longViolations =
                validator.validate(longKey);

        assertFalse(
                longViolations.isEmpty(),
                "idempotencyKey > 100 characters should be rejected"
        );

        PlaceOrderRequest minimumKey =
                new PlaceOrderRequest(
                        1001L,
                        "AAPL",
                        OrderSide.BUY,
                        10L,
                        new BigDecimal("150.25"),
                        "KEY12345"
                );

        Set<ConstraintViolation<PlaceOrderRequest>> minimumViolations =
                validator.validate(minimumKey);

        assertTrue(
                minimumViolations.isEmpty(),
                "idempotencyKey of 8 characters should be accepted"
        );

        PlaceOrderRequest maximumKey =
                new PlaceOrderRequest(
                        1001L,
                        "AAPL",
                        OrderSide.BUY,
                        10L,
                        new BigDecimal("150.25"),
                        "K".repeat(100)
                );

        Set<ConstraintViolation<PlaceOrderRequest>> maximumViolations =
                validator.validate(maximumKey);

        assertTrue(
                maximumViolations.isEmpty(),
                "idempotencyKey of 100 characters should be accepted"
        );
    }
}
