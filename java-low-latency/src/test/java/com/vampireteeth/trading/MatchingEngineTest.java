package com.vampireteeth.trading;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.Queue;

import static com.vampireteeth.trading.Main.ticks;
import static org.assertj.core.api.Assertions.assertThat;

class MatchingEngineTest {

    private MatchingEngine engine;
    private Queue<TradeEvent> trades;

    @BeforeEach
    void setUp() {
        trades = new ArrayDeque<>();
        engine = new MatchingEngine(1024, 1024, this::enqueue);
    }
    private void enqueue(TradeEvent event, long seq, boolean eob) {
        trades.offer(event);
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            // Do nothing
        }
    }

    @Test
    void shouldNotMatchAndRestingWhenBuyPriceIsSmallerThanBestAsks() {
        engine.publishOrder(1, 100, 0, ticks(200.02), 100, 1, 0);  // SELL 100 @ 200.02
        engine.publishOrder(2, 200, 0, ticks(199.98), 100, 0, 0);  // BUY 100 @ 199.98
        sleep(50);
        assertThat(trades).isEmpty();
        var book = engine.getBook(0);
        assertThat(book.bestAsk()).isEqualTo(ticks(200.02));
        assertThat(book.bestBid()).isEqualTo(ticks(199.98));
    }

    @Test
    void shouldMatchAndTradeWhenBuyPriceIsGreaterThanOrEqualToBestAsks() {
        engine.publishOrder(1, 100, 0, ticks(200.02), 100, 1, 0);  // SELL 100 @ 200.02
        engine.publishOrder(2, 200, 0, ticks(201.02), 100, 0, 0);  // BUY 100 @ 201.02
        sleep(50);
        assertThat(trades).anyMatch(it ->
                it.symbolId() == 0 && it.priceTicks() == ticks(200.02)
                        && it.sellOrderId() == 1 && it.buyOrderId() == 2);
        var book = engine.getBook(0);
        assertThat(book.bestAsk()).isEqualTo(Long.MIN_VALUE); // asks is empty
        assertThat(book.bestBid()).isEqualTo(Long.MAX_VALUE); // bids is empty
    }

    @Test
    void shouldNotMatchAndRestingWhenSellPriceIsGreaterThanBestBids() {
        engine.publishOrder(1, 100, 0, ticks(199.98), 100, 0, 0);  // BUY 100 @ 199.98
        engine.publishOrder(2, 200, 0, ticks(200.02), 100, 1, 0);  // SELL 100 @ 200.02
        sleep(50);
        assertThat(trades).isEmpty();
        var book = engine.getBook(0);
        assertThat(book.bestAsk()).isEqualTo(ticks(200.02));
        assertThat(book.bestBid()).isEqualTo(ticks(199.98));
    }

    @Test
    void shouldMatchAndTradeWhenSellPriceIsSmallerThanOrEqualToBestBids() {
        engine.publishOrder(1, 100, 0, ticks(201.02), 100, 0, 0);  // BUY 100 @ 199.98
        engine.publishOrder(2, 200, 0, ticks(200.02), 100, 1, 0);  // SELL 100 @ 200.02
        sleep(50);
        assertThat(trades).anyMatch(it ->
                it.symbolId() == 0 && it.priceTicks() == ticks(201.02)
                        && it.sellOrderId() == 2 && it.buyOrderId() == 1);
        var book = engine.getBook(0);
        assertThat(book.bestAsk()).isEqualTo(Long.MIN_VALUE); // asks is empty
        assertThat(book.bestBid()).isEqualTo(Long.MAX_VALUE); // bids is empty
    }

    @Test
    void shouldRejectSelfTrade() {
        engine.publishOrder(1, 100, 0, ticks(200.02), 100, 1, 0);  // SELL 100 @ 200.02
        engine.publishOrder(2, 100, 0, ticks(199.98), 100, 0, 0);  // BUY 100 @ 199.98
        sleep(50);
        assertThat(trades).isEmpty();
        var book = engine.getBook(0);
        assertThat(book.bestAsk()).isEqualTo(ticks(200.02));
        assertThat(book.bestBid()).isEqualTo(ticks(199.98));
    }
}