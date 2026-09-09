package com.trading.domain.order;

import com.trading.domain.exception.DuplicateOrderException;
import com.trading.domain.exception.InstrumentNotFoundException;
import com.trading.domain.instrument.Instrument;

import java.util.Map;
import java.util.Objects;
import java.util.Set;

// common checks live here, side-specific rules get delegated to OrderValidator
public class OrderValidationService {
    private final Map<Long, Instrument> instruments;
    private final Set<String> acceptedIdempotencyKeys;

    public OrderValidationService(Map<Long, Instrument> instruments,
                                  Set<String> acceptedIdempotencyKeys) {
        this.instruments = Objects.requireNonNull(instruments);
        this.acceptedIdempotencyKeys =
                Objects.requireNonNull(acceptedIdempotencyKeys);
    }

    public void isOrderValid(Order order) {
        isDuplicateOrder(order);

        Instrument instrument = isInstrumentValid(order.getInstrumentId());

        OrderValidatorFactory.forSide(order.getOrderSide())
                .validate(order, instrument);
    }

    public void isDuplicateOrder(Order order) {
        if (acceptedIdempotencyKeys.contains(order.getIdempotencyKey())) {
            throw new DuplicateOrderException();
        }
    }

    public Instrument isInstrumentValid(long instrumentId) {
        Instrument instrument = instruments.get(instrumentId);

        if (instrument == null || !instrument.isTradeable()) {
            throw new InstrumentNotFoundException();
        }

        return instrument;
    }

    public void acceptIdempotencyKey(Order order) {
        acceptedIdempotencyKeys.add(order.getIdempotencyKey());
    }
}
