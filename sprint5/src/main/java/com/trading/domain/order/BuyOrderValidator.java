package com.trading.domain.order;

import com.trading.domain.exception.InsufficientInstrumentQuantityException;
import com.trading.domain.instrument.Instrument;

public class BuyOrderValidator implements OrderValidator {

    @Override
    public void validate(Order order, Instrument instrument) {
        if (!instrument.hasQuantity(order.getQuantity())) {
            throw new InsufficientInstrumentQuantityException();
        }
    }
}
