package com.vampireteeth.trading;

import com.lmax.disruptor.RingBuffer;
import com.lmax.disruptor.dsl.Disruptor;
import com.lmax.disruptor.util.DaemonThreadFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

public class MatchingEngine {

    private final RingBuffer<OrderEvent> orderRing;
    private final RingBuffer<TradeEvent> tradeRing;
    private final Map<Integer, OrderBook> books = new HashMap<>();
    private final AtomicLong seqCounter = new AtomicLong(0);

    public MatchingEngine(int orderBufferSize, int tradeBufferSize) {
        // Trade ring (downstream)
        Disruptor<TradeEvent> tradeDisruptor = new Disruptor<>(
                TradeEvent::new,
                tradeBufferSize,
                DaemonThreadFactory.INSTANCE
        );
        tradeDisruptor.handleEventsWith((event, seq, eob) ->
                System.out.println("  [trade] " + event)
        );
        tradeDisruptor.start();
        this.tradeRing = tradeDisruptor.getRingBuffer();

        // Order ring (ingress)
        Disruptor<OrderEvent> orderDisruptor = new Disruptor<>(
                OrderEvent::new,
                orderBufferSize,
                DaemonThreadFactory.INSTANCE
        );
        orderDisruptor.handleEventsWith((event, seq, eob) ->
                onOrderEvent(event)
        );
        orderDisruptor.start();
        this.orderRing = orderDisruptor.getRingBuffer();
    }

    // ─── Called by the matching thread ONLY ───────────────────────────

    private void onOrderEvent(OrderEvent event) {
        int symbolId = event.symbolId();
        OrderBook book = books.computeIfAbsent(symbolId, k -> new OrderBook());

        switch (event.action()) {
            case 0: // NEW
                handleNew(book, event);
                break;
            case 1: // CANCEL
                // TODO: remove from book by orderId
                System.out.println("  [cancel] orderId=" + event.orderId());
                break;
            case 2: // REPLACE
                // TODO: cancel + re-insert
                break;
        }
    }

    private void handleNew(OrderBook book, OrderEvent event) {
        long price = event.priceTicks();
        long remaining = event.quantity();
        long orderId = event.orderId();
        long clientId = event.clientId();
        int side = event.side();
        int symbolId = event.symbolId();
        long seq = seqCounter.incrementAndGet();

        if (side == 0) {
            // BUY: walk the ASK side
            while (remaining > 0 && book.bestAsk() <= price) {
                Order resting = book.pollFromAsks();
                if (resting == null) break;

                // Self-trade check
                if (resting.clientId == clientId) {
                    System.out.println("  [reject] self-trade: " + orderId);
                    // put it back
                    book.insert(resting);
                    break;
                }

                long fillQty = Math.min(remaining, resting.quantity);
                emitTrade(book, orderId, resting.orderId, resting.priceTicks, fillQty, symbolId);

                remaining -= fillQty;
                resting.quantity -= fillQty;
                if (resting.quantity > 0) {
                    book.insert(resting); // put back with reduced qty
                }
            }
            // Residual rests on the bid side
            if (remaining > 0) {
                Order order = new Order();
                order.set(orderId, clientId, price, remaining, seq, 0);
                book.insert(order);
                System.out.println("  [rest] BUY " + remaining + " @ " + (price / 10_000.0));
            }
        } else {
            // SELL: walk the BID side
            while (remaining > 0 && book.bestBid() >= price) {
                Order resting = book.pollFromBids();
                if (resting == null) break;

                if (resting.clientId == clientId) {
                    System.out.println("  [reject] self-trade: " + orderId);
                    book.insert(resting);
                    break;
                }

                long fillQty = Math.min(remaining, resting.quantity);
                emitTrade(book, resting.orderId, orderId, resting.priceTicks, fillQty, symbolId);

                remaining -= fillQty;
                resting.quantity -= fillQty;
                if (resting.quantity > 0) {
                    book.insert(resting);
                }
            }
            if (remaining > 0) {
                Order order = new Order();
                order.set(orderId, clientId, price, remaining, seq, 1);
                book.insert(order);
                System.out.println("  [rest] SELL " + remaining + " @ " + (price / 10_000.0));
            }
        }
    }

    private void emitTrade(OrderBook book, long buyId, long sellId, long priceTicks, long qty, int symbolId) {
        tradeRing.publishEvent((event, seq) ->
                event.set(buyId, sellId, priceTicks, qty, symbolId, System.nanoTime())
        );
    }

    // ─── Public API ───────────────────────────────────────────────────

    public void publishOrder(long orderId, long clientId, int symbolId,
                             long priceTicks, long quantity, int side, int action) {
        orderRing.publishEvent((event, seq) -> {
            event.orderId = orderId;
            event.clientId = clientId;
            event.symbolId = symbolId;
            event.priceTicks = priceTicks;
            event.quantity = quantity;
            event.side = side;
            event.action = action;
            event.seq = seq;
        });
    }

    public OrderBook getBook(int symbolId) {
        return books.get(symbolId);
    }
}