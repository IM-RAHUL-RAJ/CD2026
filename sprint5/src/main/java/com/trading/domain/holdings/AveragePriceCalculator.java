package com.trading.domain.holdings;

import java.math.BigDecimal;

// pulled out of Holdings so the weighted-average math isn't buried in a setter
@FunctionalInterface
public interface AveragePriceCalculator {
    BigDecimal calculate(long currentQuantity, BigDecimal currentAveragePrice,
                        long addedQuantity, BigDecimal addedPrice);
}
