package com.vampireteeth.trading;

public class Order {
    public long orderId;
    public long clientId;
    public long priceTicks;
    public long quantity;
    public long seq;       // arrival order (FIFO within a price level)
    public int side;       // 0 = BUY, 1 = SELL

    public Order() {}

    public void set(long orderId, long clientId, long priceTicks, long quantity, long seq, int side) {
        this.orderId = orderId;
        this.clientId = clientId;
        this.priceTicks = priceTicks;
        this.quantity = quantity;
        this.seq = seq;
        this.side = side;
    }
}