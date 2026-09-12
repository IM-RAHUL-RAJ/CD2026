package com.trading.domain.order;

public final class OrderValidatorFactory {
    private static final OrderValidator BUY_VALIDATOR = new BuyOrderValidator();
    private static final OrderValidator SELL_VALIDATOR = new SellOrderValidator();

    private OrderValidatorFactory() {
    }

    public static OrderValidator forSide(OrderSide side) {
        return switch (side) {
            case BUY -> BUY_VALIDATOR;
            case SELL -> SELL_VALIDATOR;
        };
    }
}
