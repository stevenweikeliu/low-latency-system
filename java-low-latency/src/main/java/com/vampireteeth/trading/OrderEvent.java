package com.vampireteeth.trading;

public class OrderEvent {
    long orderId;
    long clientId;
    int symbolId;
    long priceTicks;   // fixed-point: price * 10_000 (4 decimal places)
    long quantity;
    int side;          // 0 = BUY, 1 = SELL
    int action;        // 0 = NEW, 1 = CANCEL, 2 = REPLACE
    long seq;          // arrival sequence (time-priority tiebreaker)

    public void reset() {
        orderId = 0;
        clientId = 0;
        symbolId = 0;
        priceTicks = 0;
        quantity = 0;
        side = 0;
        action = 0;
        seq = 0;
    }

    // Getters
    public long orderId() {
        return orderId;
    }

    public long clientId() {
        return clientId;
    }

    public int symbolId() {
        return symbolId;
    }

    public long priceTicks() {
        return priceTicks;
    }

    public long quantity() {
        return quantity;
    }

    public int side() {
        return side;
    }

    public int action() {
        return action;
    }

    public long seq() {
        return seq;
    }

}
