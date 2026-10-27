package com.vampireteeth.trading;

public class Main {

    // Price helper: 199.98 → 1_999_800 ticks (4 dp)
    static long ticks(double price) {
        return Math.round(price * 10_000);
    }

    public static void main() throws Exception {

        MatchingEngine engine = new MatchingEngine(1024, 1024);

        System.out.println("=== Publishing test orders ===\n");

        // 1. Rest a SELL on the ask side
        engine.publishOrder(1, 100, 0, ticks(200.02), 100, 1, 0);  // SELL 100 @ 200.02
        Thread.sleep(50);

        // 2. Rest another SELL at a better price
        engine.publishOrder(2, 200, 0, ticks(200.01), 60, 1, 0);   // SELL 60 @ 200.01
        Thread.sleep(50);

        // 3. Rest a BUY on the bid side
        engine.publishOrder(3, 300, 0, ticks(199.98), 100, 0, 0);  // BUY 100 @ 199.98
        Thread.sleep(50);

        // 4. Aggressive BUY that crosses the ask side
        engine.publishOrder(4, 400, 0, ticks(200.02), 130, 0, 0);  // BUY 130 @ 200.02
        Thread.sleep(200);

        System.out.println("\n=== Final book state ===");
        System.out.println(engine.getBook(0));

        System.out.println("\nDone. Press Enter to exit.");
        System.in.read();
        System.exit(0);
    }
}