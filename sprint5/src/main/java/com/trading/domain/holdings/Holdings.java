package com.trading.domain.holdings;

import com.trading.domain.dto.HoldingsUpdateRequest;
import com.trading.domain.exception.InsufficientHoldingsException;

import java.math.BigDecimal;
import java.math.RoundingMode;

public class Holdings {
    private static final AveragePriceCalculator WEIGHTED_AVERAGE =
            (currentQuantity, currentAveragePrice, addedQuantity, addedPrice) -> {
                BigDecimal oldValue = currentAveragePrice
                        .multiply(BigDecimal.valueOf(currentQuantity));
                BigDecimal newValue =
                        addedPrice.multiply(BigDecimal.valueOf(addedQuantity));

                return oldValue.add(newValue)
                        .divide(BigDecimal.valueOf(currentQuantity + addedQuantity),
                                2, RoundingMode.HALF_UP);
            };

    private final long accountId;
    private final long instrumentId;
    private long quantity;
    private BigDecimal averagePrice;

    public Holdings(HoldingsUpdateRequest request) {
        this(request.accountId(), request.instrumentId(),
                request.quantity(), request.averagePrice());
    }

    public Holdings(long accountId, long instrumentId,
                    long quantity, BigDecimal averagePrice) {
        if (accountId < 1 || instrumentId < 1 || quantity < 0) {
            throw new IllegalArgumentException("Invalid holdings");
        }
        if (averagePrice == null || averagePrice.scale() > 2
                || averagePrice.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("Invalid averagePrice");
        }

        this.accountId = accountId;
        this.instrumentId = instrumentId;
        this.quantity = quantity;
        this.averagePrice = averagePrice.setScale(2);
    }

    public long getAccountId() {
        return accountId;
    }

    public long getInstrumentId() {
        return instrumentId;
    }

    public long getQuantity() {
        return quantity;
    }

    public BigDecimal getAveragePrice() {
        return averagePrice;
    }

    public boolean canSell(long requestedQuantity) {
        return requestedQuantity > 0 && requestedQuantity <= quantity;
    }

    public void buy(long addedQuantity, BigDecimal price) {
        if (addedQuantity <= 0 || price == null
                || price.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Invalid buy");
        }

        averagePrice = WEIGHTED_AVERAGE.calculate(
                quantity, averagePrice, addedQuantity, price);
        quantity += addedQuantity;
    }

    public void sell(long soldQuantity) {
        if (!canSell(soldQuantity)) {
            throw new InsufficientHoldingsException();
        }

        quantity -= soldQuantity;
        // Average price intentionally remains unchanged on SELL.
    }
}
