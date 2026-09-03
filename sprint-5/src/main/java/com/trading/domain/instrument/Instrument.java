package com.trading.domain.instrument;

import com.trading.domain.exception.InsufficientInstrumentQuantityException;

import java.math.BigDecimal;

public class Instrument {
    private final long instrumentId;
    private final String symbol;
    private final String assetClass;
    private final String currency;
    private boolean tradeable;
    private BigDecimal price;
    private long quantityAvailable;

    public Instrument(long instrumentId, String symbol, String assetClass,
                      String currency, boolean tradeable,
                      BigDecimal price, long quantityAvailable) {
        if (instrumentId < 1) {
            throw new IllegalArgumentException("instrumentId must be positive");
        }
        if (symbol == null || symbol.isBlank() || symbol.length() > 20) {
            throw new IllegalArgumentException("Invalid symbol");
        }
        if (assetClass == null || assetClass.isBlank()) {
            throw new IllegalArgumentException("assetClass is required");
        }
        if (currency == null || currency.isBlank()) {
            throw new IllegalArgumentException("currency is required");
        }
        if (price == null || price.scale() > 2
                || price.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException(
                    "price must be positive with at most 2 decimal places");
        }
        if (quantityAvailable < 0) {
            throw new IllegalArgumentException("quantityAvailable cannot be negative");
        }

        this.instrumentId = instrumentId;
        this.symbol = symbol;
        this.assetClass = assetClass;
        this.currency = currency;
        this.tradeable = tradeable;
        this.price = price.setScale(2);
        this.quantityAvailable = quantityAvailable;
    }

    public long getInstrumentId() {
        return instrumentId;
    }

    public String getSymbol() {
        return symbol;
    }

    public String getAssetClass() {
        return assetClass;
    }

    public String getCurrency() {
        return currency;
    }

    public boolean isTradeable() {
        return tradeable;
    }

    public BigDecimal getPrice() {
        return price;
    }

    public long getQuantityAvailable() {
        return quantityAvailable;
    }

    public void setTradeable(boolean tradeable) {
        this.tradeable = tradeable;
    }

    public void setPrice(BigDecimal price) {
        if (price == null || price.scale() > 2
                || price.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Invalid price");
        }

        this.price = price.setScale(2);
    }

    public boolean hasQuantity(long quantity) {
        return quantity > 0 && quantity <= quantityAvailable;
    }

    public void reserveQuantity(long quantity) {
        if (!hasQuantity(quantity)) {
            throw new InsufficientInstrumentQuantityException();
        }

        quantityAvailable -= quantity;
    }
}
