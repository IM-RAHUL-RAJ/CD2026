package com.trading.domain.dto;

import java.math.BigDecimal;

// used to open a brand new Holdings row the first time an account trades an instrument
public record HoldingsUpdateRequest(
        long accountId,
        long instrumentId,
        long quantity,
        BigDecimal averagePrice) {
}