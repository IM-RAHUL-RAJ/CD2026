package com.trading.tradeapi.controller;

import com.trading.tradeapi.dto.LoginRequestDto;
import com.trading.tradeapi.dto.TokenResponseDto;
import com.trading.tradeapi.security.JwtService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

@RestController
@RequestMapping("/auth")
public class AuthController {

    private final JwtService jwtService;

    public AuthController(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    @PostMapping("/login")
    public ResponseEntity<TokenResponseDto> login(@Valid @RequestBody LoginRequestDto request) {
        Long accountId = request.accountId();
        String token = jwtService.generateToken(accountId);

        Instant issuedAt = Instant.now();
        // 1 hour default expiration (3600000 ms)
        Instant expiresAt = issuedAt.plusSeconds(3600);

        TokenResponseDto response = new TokenResponseDto(
                token,
                "Bearer",
                accountId,
                issuedAt,
                expiresAt
        );

        return ResponseEntity.ok(response);
    }
}
