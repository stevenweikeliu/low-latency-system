package com.vampireteeth.trading;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;

// This is an on-heap implementation of the order book
// no performance tuning is done here

public class OrderBook {
    // Bid side: reverse order (highest price = best)
    private final TreeMap<Long, ArrayDeque<Order>> bids = new TreeMap<>(Collections.reverseOrder());
    // Ask side: natural order (lowest price = best)
    private final TreeMap<Long, ArrayDeque<Order>> asks = new TreeMap<>();

    public long bestBid() {
        return bids.isEmpty() ? Long.MAX_VALUE : bids.firstKey();
    }

    public long bestAsk() {
        return asks.isEmpty() ? Long.MIN_VALUE : asks.firstKey();
    }


    public void insertNewOrder(long orderId, long clientId, long price, long remaining, long seq, int side) {
        Order order = new Order();
        order.set(orderId, clientId, price, remaining, seq, side);
        this.insert(order);
    }

    public void insert(Order order) {
        TreeMap<Long, ArrayDeque<Order>> book = (order.side == 0) ? bids : asks;
        book.computeIfAbsent(order.priceTicks, k -> new ArrayDeque<>()).addLast(order);
    }

    public Order pollFromBids() {
        if (bids.isEmpty()) return null;
        long best = bestBid();
        ArrayDeque<Order> queue = bids.get(best);
        Order order = queue.pollFirst();
        if (queue.isEmpty()) bids.remove(best);
        return order;
    }

    public Order pollFromAsks() {
        if (asks.isEmpty()) return null;
        long best = asks.firstKey();
        ArrayDeque<Order> queue = asks.get(best);
        Order order = queue.pollFirst();
        if (queue.isEmpty()) asks.remove(best);
        return order;
    }

    // For testing / debugging
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("BIDS:");
        for (Map.Entry<Long, ArrayDeque<Order>> e : bids.entrySet()) {
            sb.append("\n  ").append(e.getKey());
            for (Order o : e.getValue()) sb.append(" [").append(o.orderId).append("(").append(o.quantity).append(")");
            sb.append("]");
        }
        sb.append("\nASKS:");
        for (Map.Entry<Long, ArrayDeque<Order>> e : asks.entrySet()) {
            sb.append("\n  ").append(e.getKey());
            for (Order o : e.getValue()) sb.append(" [").append(o.orderId).append("(").append(o.quantity).append(")");
            sb.append("]");
        }
        return sb.toString();
    }
}
