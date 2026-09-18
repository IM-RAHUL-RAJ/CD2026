package com.trading.tradeapi;

import com.trading.tradeapi.dto.ErrorResponseDto;
import com.trading.tradeapi.exception.DomainException;
import com.trading.tradeapi.exception.GlobalExceptionHandler;
import com.trading.tradeapi.exception.AccountNotFoundException;
import com.trading.tradeapi.exception.AccountNotActiveException;
import com.trading.tradeapi.exception.InstrumentNotFoundException;
import com.trading.tradeapi.exception.InsufficientFundsException;
import com.trading.tradeapi.exception.InsufficientHoldingsException;
import com.trading.tradeapi.exception.DuplicateOrderException;
import com.trading.tradeapi.exception.OrderNotFoundException;
import com.trading.tradeapi.exception.UnauthorizedAccountAccessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Path;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class GlobalExceptionHandlerUnitTest {

    private GlobalExceptionHandler exceptionHandler;

    @BeforeEach
    public void setUp() {
        exceptionHandler = new GlobalExceptionHandler();
    }

    // ======================== DomainException Handler Tests ========================

    @Test
    public void handleDomainExceptionAccountNotFoundReturns404() {
        DomainException ex = new AccountNotFoundException();

        ResponseEntity<ErrorResponseDto> response = exceptionHandler.handleDomainException(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().errorCode()).isEqualTo("ACC-404");
        assertThat(response.getBody().message()).isEqualTo("Account not found");
    }

    @Test
    public void handleDomainExceptionAccountNotActiveReturns403() {
        DomainException ex = new AccountNotActiveException();

        ResponseEntity<ErrorResponseDto> response = exceptionHandler.handleDomainException(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().errorCode()).isEqualTo("ACC-403");
        assertThat(response.getBody().message()).isEqualTo("Account is not active");
    }

    @Test
    public void handleDomainExceptionInstrumentNotFoundReturns404() {
        DomainException ex = new InstrumentNotFoundException();

        ResponseEntity<ErrorResponseDto> response = exceptionHandler.handleDomainException(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().errorCode()).isEqualTo("INS-404");
    }

    @Test
    public void handleDomainExceptionInsufficientFundsReturns400() {
        DomainException ex = new InsufficientFundsException();

        ResponseEntity<ErrorResponseDto> response = exceptionHandler.handleDomainException(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().errorCode()).isEqualTo("ORD-400");
    }

    @Test
    public void handleDomainExceptionInsufficientHoldingsReturns409() {
        DomainException ex = new InsufficientHoldingsException();

        ResponseEntity<ErrorResponseDto> response = exceptionHandler.handleDomainException(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().errorCode()).isEqualTo("ORD-409");
    }

    @Test
    public void handleDomainExceptionDuplicateOrderReturns409() {
        DomainException ex = new DuplicateOrderException();

        ResponseEntity<ErrorResponseDto> response = exceptionHandler.handleDomainException(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().errorCode()).isEqualTo("ORD-409");
        assertThat(response.getBody().message()).isEqualTo("Duplicate order");
    }

    @Test
    public void handleDomainExceptionOrderNotFoundReturns409() {
        DomainException ex = new OrderNotFoundException();

        ResponseEntity<ErrorResponseDto> response = exceptionHandler.handleDomainException(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().errorCode()).isEqualTo("ORD-409");
    }

    @Test
    public void handleDomainExceptionUnauthorizedAccountAccessReturns401() {
        DomainException ex = new UnauthorizedAccountAccessException();

        ResponseEntity<ErrorResponseDto> response = exceptionHandler.handleDomainException(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().errorCode()).isEqualTo("ACC-401");
    }

    @Test
    public void handleDomainExceptionWithNullMessageUsesDefaultMessage() {
        DomainException ex = new DomainException("TEST-500", null, HttpStatus.INTERNAL_SERVER_ERROR);

        ResponseEntity<ErrorResponseDto> response = exceptionHandler.handleDomainException(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().message()).isEqualTo("An error occurred");
    }

    // ======================== MethodArgumentNotValidException Handler Tests ========================

    @Test
    public void handleMethodArgumentNotValidSingleFieldErrorReturns422() {
        MethodArgumentNotValidException ex = mock(MethodArgumentNotValidException.class);
        FieldError fieldError = new FieldError("dto", "price", "Price must be greater than 0");
        org.springframework.validation.BindingResult bindingResult = mock(org.springframework.validation.BindingResult.class);
        when(bindingResult.getFieldErrors()).thenReturn(java.util.List.of(fieldError));
        when(ex.getBindingResult()).thenReturn(bindingResult);

        ResponseEntity<ErrorResponseDto> response = exceptionHandler.handleMethodArgumentNotValid(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().errorCode()).isEqualTo("VAL-422");
        assertThat(response.getBody().message()).contains("price");
        assertThat(response.getBody().message()).contains("Price must be greater than 0");
    }

    @Test
    public void handleMethodArgumentNotValidMultipleFieldErrorsReturns422() {
        MethodArgumentNotValidException ex = mock(MethodArgumentNotValidException.class);
        FieldError error1 = new FieldError("dto", "price", "Price must be greater than 0");
        FieldError error2 = new FieldError("dto", "quantity", "Quantity must be at least 1");
        org.springframework.validation.BindingResult bindingResult = mock(org.springframework.validation.BindingResult.class);
        when(bindingResult.getFieldErrors()).thenReturn(java.util.List.of(error1, error2));
        when(ex.getBindingResult()).thenReturn(bindingResult);

        ResponseEntity<ErrorResponseDto> response = exceptionHandler.handleMethodArgumentNotValid(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().errorCode()).isEqualTo("VAL-422");
        assertThat(response.getBody().message()).contains("price");
        assertThat(response.getBody().message()).contains("quantity");
    }

    @Test
    public void handleMethodArgumentNotValidEmptyErrorsUsesDefaultMessage() {
        MethodArgumentNotValidException ex = mock(MethodArgumentNotValidException.class);
        org.springframework.validation.BindingResult bindingResult = mock(org.springframework.validation.BindingResult.class);
        when(bindingResult.getFieldErrors()).thenReturn(java.util.List.of());
        when(ex.getBindingResult()).thenReturn(bindingResult);

        ResponseEntity<ErrorResponseDto> response = exceptionHandler.handleMethodArgumentNotValid(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().message()).isEqualTo("Invalid input");
    }

    // ======================== ConstraintViolationException Handler Tests ========================

    @Test
    public void handleConstraintViolationsSingleViolationReturns422() {
        @SuppressWarnings("unchecked")
        ConstraintViolation<Object> violation = mock(ConstraintViolation.class);
        Path mockPath = mock(Path.class);
        when(mockPath.toString()).thenReturn("symbol");
        when(violation.getPropertyPath()).thenReturn(mockPath);
        when(violation.getMessage()).thenReturn("Symbol must not be empty");

        ConstraintViolationException ex = new ConstraintViolationException(Set.of(violation));

        ResponseEntity<ErrorResponseDto> response = exceptionHandler.handleConstraintViolations(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().errorCode()).isEqualTo("VAL-422");
        assertThat(response.getBody().message()).contains("symbol");
    }

    @Test
    public void handleConstraintViolationsMultipleViolationsReturns422() {
        @SuppressWarnings("unchecked")
        ConstraintViolation<Object> violation1 = mock(ConstraintViolation.class);
        Path mockPath1 = mock(Path.class);
        when(mockPath1.toString()).thenReturn("price");
        when(violation1.getPropertyPath()).thenReturn(mockPath1);
        when(violation1.getMessage()).thenReturn("Price must be positive");

        @SuppressWarnings("unchecked")
        ConstraintViolation<Object> violation2 = mock(ConstraintViolation.class);
        Path mockPath2 = mock(Path.class);
        when(mockPath2.toString()).thenReturn("quantity");
        when(violation2.getPropertyPath()).thenReturn(mockPath2);
        when(violation2.getMessage()).thenReturn("Quantity must be positive");

        ConstraintViolationException ex = new ConstraintViolationException(Set.of(violation1, violation2));

        ResponseEntity<ErrorResponseDto> response = exceptionHandler.handleConstraintViolations(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().errorCode()).isEqualTo("VAL-422");
    }

    // ======================== IllegalArgumentException Handler Tests ========================

    @Test
    public void handleIllegalArgumentWithMessageReturns422() {
        IllegalArgumentException ex = new IllegalArgumentException("Invalid order side");

        ResponseEntity<ErrorResponseDto> response = exceptionHandler.handleIllegalArgument(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().errorCode()).isEqualTo("VAL-422");
        assertThat(response.getBody().message()).isEqualTo("Invalid order side");
    }

    @Test
    public void handleIllegalArgumentWithoutMessageReturns422() {
        IllegalArgumentException ex = new IllegalArgumentException();

        ResponseEntity<ErrorResponseDto> response = exceptionHandler.handleIllegalArgument(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().errorCode()).isEqualTo("VAL-422");
        assertThat(response.getBody().message()).isEqualTo("Invalid input");
    }

    // ======================== Generic Exception Handler Tests ========================

    @Test
    public void handleGenericExceptionWithMessageReturns500() {
        Exception ex = new RuntimeException("Database connection error");

        ResponseEntity<ErrorResponseDto> response = exceptionHandler.handleGenericException(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().errorCode()).isEqualTo("SRV-500");
        assertThat(response.getBody().message()).isEqualTo("Database connection error");
    }

    @Test
    public void handleGenericExceptionWithoutMessageReturns500() {
        Exception ex = new RuntimeException();

        ResponseEntity<ErrorResponseDto> response = exceptionHandler.handleGenericException(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().errorCode()).isEqualTo("SRV-500");
        assertThat(response.getBody().message()).isEqualTo("Internal server error");
    }

    @Test
    public void handleGenericExceptionWrapsUnexpectedExceptions() {
        Exception ex = new Exception("Unexpected error");

        ResponseEntity<ErrorResponseDto> response = exceptionHandler.handleGenericException(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().errorCode()).isEqualTo("SRV-500");
    }
}
