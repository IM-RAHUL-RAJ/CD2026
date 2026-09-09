package com.trading.domain.order;

import com.trading.domain.instrument.Instrument;

// BUY and SELL each get their own rules instead of one big if/else
public interface OrderValidator {
    void validate(Order order, Instrument instrument);
}
