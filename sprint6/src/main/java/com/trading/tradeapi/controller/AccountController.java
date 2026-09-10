package com.trading.tradeapi.controller;

import com.trading.tradeapi.exception.AccountNotActiveException;
import com.trading.tradeapi.dto.AccountResponseDto;
import com.trading.tradeapi.dto.BalanceResponseDto;
import com.trading.tradeapi.dto.OrderHistoryEntryDto;
import com.trading.tradeapi.dto.PositionResponseDto;
import com.trading.tradeapi.security.JwtAuthenticationFilter;
import com.trading.tradeapi.service.TradeService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

@RestController
@RequestMapping("/api/v1/accounts")
public class AccountController {

    private final TradeService tradeService;

    public AccountController(TradeService tradeService) {
        this.tradeService = tradeService;
    }

    private void checkAccountAccess(Long targetAccountId, HttpServletRequest request) {
        Long authAccountId = (Long) request.getAttribute(JwtAuthenticationFilter.AUTHENTICATED_ACCOUNT_ID_ATTR);
        if (authAccountId != null && !authAccountId.equals(targetAccountId)) {
            throw new AccountNotActiveException();
        }
    }

    @GetMapping("/{id}")
    public ResponseEntity<AccountResponseDto> getAccount(@PathVariable("id") Long id,
                                                         HttpServletRequest request) {
        checkAccountAccess(id, request);
        return ResponseEntity.ok(tradeService.getAccount(id));
    }

    @GetMapping("/{id}/balance")
    public ResponseEntity<BalanceResponseDto> getBalance(@PathVariable("id") Long id,
                                                         HttpServletRequest request) {
        checkAccountAccess(id, request);
        return ResponseEntity.ok(tradeService.getBalance(id));
    }

    @GetMapping("/{id}/positions")
    public ResponseEntity<List<PositionResponseDto>> getPositions(@PathVariable("id") Long id,
                                                                 HttpServletRequest request) {
        checkAccountAccess(id, request);
        return ResponseEntity.ok(tradeService.getPositions(id));
    }

    @GetMapping("/{id}/orders")
    public ResponseEntity<List<OrderHistoryEntryDto>> getOrders(
            @PathVariable("id") Long id,
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "from", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(value = "to", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            HttpServletRequest request) {
        checkAccountAccess(id, request);
        return ResponseEntity.ok(tradeService.getOrders(id, status, from, to));
    }
}
