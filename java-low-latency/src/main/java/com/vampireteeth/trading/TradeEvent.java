package com.vampireteeth.trading;

public class TradeEvent {
    private long buyOrderId;
    private long sellOrderId;
    private long priceTicks;
    private long quantity;
    private int symbolId;
    private long timestampNs;

    public void set(long buyId, long sellId, long priceTicks, long qty, int symbolId, long ts) {
        this.buyOrderId = buyId;
        this.sellOrderId = sellId;
        this.priceTicks = priceTicks;
        this.quantity = qty;
        this.symbolId = symbolId;
        this.timestampNs = ts;
    }

    public long buyOrderId() {
        return buyOrderId;
    }

    public long sellOrderId() {
        return sellOrderId;
    }

    public long priceTicks() {
        return priceTicks;
    }

    public long quantity() {
        return quantity;
    }

    public int symbolId() {
        return symbolId;
    }

    public long timestampNs() {
        return timestampNs;
    }

    @Override
    public String toString() {
        return String.format("TRADE buy=%d sell=%d @ %.4f x %d",
                buyOrderId, sellOrderId, priceTicks / 10_000.0, quantity);
    }
}