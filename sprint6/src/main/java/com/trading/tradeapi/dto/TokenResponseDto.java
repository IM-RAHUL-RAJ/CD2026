package com.trading.tradeapi.dto;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.Instant;

public record TokenResponseDto(
        String token,
        String tokenType,
        Long accountId,
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss'Z'", timezone = "UTC")
        Instant issuedAt,
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss'Z'", timezone = "UTC")
        Instant expiresAt
) {}
