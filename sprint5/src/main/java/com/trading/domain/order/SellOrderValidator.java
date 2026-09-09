package com.trading.domain.order;

import com.trading.domain.instrument.Instrument;

public class SellOrderValidator implements OrderValidator {

    @Override
    public void validate(Order order, Instrument instrument) {
        // sell holdings check happens in the executor, where we actually have the holdings
    }
}
