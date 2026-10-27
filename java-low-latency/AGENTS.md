```markdown
# Matching Engine — Agent Guide

## Project

A single-threaded, price-time priority order matching engine built on:
- **Disruptor** (LMAX) — lock-free ring buffer for intra-process event flow
- **Agrona** — off-heap buffers (used later for WAL / book migration)
- **Java 25**, Gradle (Kotlin DSL), Shadow plugin for fat JAR

No Spring. No gRPC. No Kafka. This is a single JVM, single-thread hot path.

---

## Build & Run

```bash
./gradlew shadowJar
java -cp build/libs/matching-engine-0.0.1-SNAPSHOT.jar com.vampireteeth.trading.Main
```

Tests:
```bash
./gradlew test
```

---

## Trading Terminology

### Order Book

Two sides:
- **Bids** (buy orders): "I will *buy* at this price or better." Sorted descending (highest = best).
- **Asks** (sell orders): "I will *sell* at this price or better." Sorted ascending (lowest = best).

Each side is a map: `price → FIFO queue of orders`.

```
BIDS (descending)              ASKS (ascending)
─────────────────────          ─────────────────────
199.98  [A(100), B(50)]      200.01  [C(60), D(40)]
199.97  [E(200)]             200.02  [F(150)]
```

- **Best bid** = highest price on bid side
- **Best ask** = lowest price on ask side
- **Spread** = best ask − best bid
- **Mid-price** = (best bid + best ask) / 2 (display only, never a trade price)

### Price-Time Priority

Two rules, applied in order:
1. **Price priority**: better price fills first, regardless of arrival time.
2. **Time priority**: at the same price, FIFO (oldest order fills first).

### Limit Price Semantics

A limit price is a **bound**, not a target:
- BUY limit 200.05 means "I will not pay *more than* 200.05" — you may fill at 200.01, 200.02, etc.
- SELL limit 199.97 means "I will not sell for *less than* 199.97" — you may fill at 199.98, 200.01, etc.

### Crossing & Execution

A **trade occurs** when a new order's price crosses the opposite side:
- BUY at price P crosses if P ≥ best ask
- SELL at price P crosses if P ≤ best bid

If it doesn't cross, the order **rests** on the book (joins the queue at its price level).

### Trade (Execution) Price

**Always the resting order's price**, never the aggressor's.

Example: resting BUY 100 @ 190.00, incoming SELL 40 @ 185.00 → trade executes at **190.00**.

Rationale: rewards the liquidity provider. The person who posted the order gets their price; the aggressor gets filled at that posted price.

### Market Maker & Spread

A **market maker** posts both a bid and an ask simultaneously. They earn the spread when both sides fill:

```
MM posts: BID 199.98 / ASK 200.02
Alice sells to MM at 199.98 (MM is now long 100 shares)
Bob buys from MM at 200.02 (MM is now flat)
MM profit = (200.02 − 199.98) × 100 = $400
```

The spread is compensation for **inventory risk** (the price might move between the buy and the sell). The exchange does *not* capture the spread — it charges transaction fees, market data subscriptions, and colocation.

### Adverse Selection

The MM's main risk: an informed trader hits their quote just before a price move. The MM's $0.04 spread is dwarfed by a $5.00 adverse move. This is why MMs widen spreads or pull quotes during volatility.

---

## Matching Algorithm (Pseudocode)

```
onNewOrder(order):
    if order.side == BUY:
        while order.remaining > 0 AND bestAsk <= order.price:
            resting = pollFromAsks()          // FIFO at best price level
            if resting.clientId == order.clientId:
                reject (self-trade); break
            fillQty = min(order.remaining, resting.quantity)
            emitTrade(order.id, resting.id, resting.price, fillQty)
            order.remaining -= fillQty
            resting.quantity -= fillQty
            if resting.quantity > 0: re-insert resting
        if order.remaining > 0:
            rest on bid side at order.price

    if order.side == SELL: (symmetric — walk bid side)
```

### Edge Cases

| Case | Handling |
|---|---|
| Partial fill, residual doesn't cross further | Residual rests at aggressor's limit price |
| Market order (no limit) | Walks entire opposite side; leftover is cancelled |
| Self-trade (same client both sides) | Reject aggressor (or cancel one side — pick a rule) |
| Cancel | Remove from queue by orderId |
| Replace (amend) | Cancel + re-insert. Loses time priority. |
| Same-timestamp tiebreaker | Use Disruptor sequence number |

---

## Architecture

### Threading Model

```
Ingress thread(s)
    │ publishEvent
    ▼
Order Ring (Disruptor, 1024 slots)
    │
    ▼
MATCHING THREAD (single, pinned to a dedicated core)
    │
    ├── match orders (TreeMap ops, ~200 ns)
    ├── tradeRing.publishEvent(...)       (~20 ns, emit only)
    └── bookUpdateRing.publishEvent(...)  (~20 ns, emit only)
                │                    │
                ▼                    ▼
        Trade Ring (1024)    Book Update Ring (1024)
                │                    │
                ▼                    ▼
        Consumer thread A    Consumer thread B
        (WAL, risk)          (market data broadcast)
```

**Critical rule**: the matching thread does NO I/O. It only:
1. Mutates the in-memory book (TreeMap)
2. Publishes events to downstream rings (~20 ns each)

All I/O (disk, network, DB) happens on consumer threads.

### Backpressure

If a consumer is too slow, its ring fills. The matching thread's next `publishEvent` will spin/yield/sleep until a slot frees. Mitigations:
- Make consumer handlers fast (memory copy only; defer I/O to a background flusher)
- Use larger rings (4096/8192) for non-critical events
- Trade events: must never drop. Market data: can drop.

### WAL (Write-Ahead Log)

- **WAL buffer**: in-memory staging area (Agrona `AtomicBuffer` or `ByteBuffer`)
- **Consumer thread**: serializes trade into buffer (~10 ns), returns immediately
- **Background flusher**: every 1 ms or 64 events → `FileChannel.write()` + `force()` (fsync)
- **Snapshot**: periodic full book dump. On restart: load snapshot + replay WAL from that point.

No external WAL library. ~50 lines of `FileChannel` + `AtomicBuffer`.

### Data Structures

| Structure | Current (Day 1–3) | Later (optimisation) |
|---|---|---|
| Order book | `TreeMap<Long, ArrayDeque<Order>>` × 2 (in-heap) | Agrona off-heap, indexed by price ticks |
| Ring buffer events | Plain Java objects (pre-allocated by Disruptor) | Same (already zero-alloc) |
| WAL buffer | `ByteBuffer` (direct) | Agrona `AtomicBuffer` |

Migrate to off-heap only when GC pauses show up in profiling.

### Price Representation

**Fixed-point integers**, not floating point. `priceTicks = price × 10_000` (4 decimal places). Avoids all FP rounding issues.

### Symbol Representation

`int symbolId`, not a string. Map symbols to IDs at startup.

---

## Key Design Decisions & Constraints

| Decision | Rationale |
|---|---|
| Single matching thread per symbol | No locks, no CAS, deterministic ordering |
| Disruptor for intra-process handoff | ~20 ns publish, zero GC, lock-free |
| No Kafka / no gRPC in the hot path | Network I/O = ms; matching = ns. Different latency classes. |
| No Spring Boot | 200 ms startup, BOM overrides, unnecessary DI for a single-thread loop |
| TreeMap for initial book | O(log n) per op, ~100–200 ns, correct. Optimise after profiling. |
| Trade price = resting order's price | Industry standard. Rewards liquidity providers. |
| Fixed-point prices | No FP bugs, trivially serializable, fast comparisons |
| Fat JAR via Shadow plugin | `mergeServiceFiles()`, proper JAR streaming, `runShadow` task |

### What NOT to do

- Don't add locks or `synchronized` to the matching thread
- Don't allocate objects in the hot path (events are pre-allocated and reused)
- Don't do I/O in the matching thread
- Don't use `double` for prices
- Don't use `String` for symbols in the hot path
- Don't add Kafka/gRPC between the matching thread and its consumers
- Don't optimise before profiling (TreeMap is fine until you prove it isn't)

---

## Disruptor Internals (Reference)

### How the Ring Buffer Works

- Fixed-size circular array of pre-allocated event objects (size = power of 2)
- All coordination via **sequence numbers** (monotonically increasing longs) + memory barriers
- No locks. Uses CAS / sequence numbers.

### Producer: Claim → Fill → Publish

1. **Claim** (`ringBuffer.next()`): single producer increments cursor; multi-producer uses CAS. Spins if buffer full.
2. **Fill**: write into the pre-allocated event. No allocation, no locking.
3. **Publish** (`ringBuffer.publish(sequence)`): single release write to the cursor. Makes the event visible to consumers.

### Consumer Duty Cycle

```
while (running) {
    long available = sequenceBarrier.waitFor(nextSequence);
    while (nextSequence <= available) {
        event = ringBuffer.get(nextSequence);
        handler.onEvent(event, nextSequence, nextSequence == available);
        nextSequence++;
    }
    mySequence.set(available);  // gating sequence
}
```

### Why It's Fast

| Technique | Effect |
|---|---|
| Pre-allocated ring | Zero GC pressure after startup |
| Power-of-2 size | `sequence & (size - 1)` replaces `%` |
| Cache line padding | Each `Sequence` on its own 64-byte cache line (no false sharing) |
| Single-writer principle | Each sequence has exactly one writer |
| Memory barriers over locks | Release/acquire semantics instead of mutex |
| Batching | Consumers amortize sync cost over many events |

### Cache Line Size Note

The Disruptor hardcodes 64-byte padding (x86/ARM standard). On 128-byte-line hardware (Apple Silicon, POWER), protection is partial. For Apple Silicon, double the padding to 128 bytes or use `@Contended` (JDK 15+, requires `-XX:-RestrictContended`).

### Agrona `AtomicBuffer`

- **Not lock-based.** Uses `sun.misc.Unsafe` for direct memory ops.
- `putLong`/`getLong` = plain `MOV` (~1 ns)
- `putLongVolatile`/`getLongVolatile` = `MOV` + store-load barrier (~1–2 ns)
- `compareAndSetLong` = `CMPXCHG` (hardware atomic, no lock)
- Default byte order = native (little-endian on x86/ARM). No action needed for same-machine WAL replay.

---

## JVM & OS Tuning (Later)

```bash
java \
  -XX:+UseZGC \
  -XX:ZCollectionInterval=10000 \
  -XX:+AlwaysPreTouch \
  -XX:ReservedCodeCacheSize=256m \
  -Xms4g -Xmx4g \
  -jar matching-engine.jar
```

OS:
- Pin matching thread: `taskset -c 2 java ...`
- `cpupower frequency-set -g performance`
- `isolcpus=2-3` in kernel params
- Disable turbo boost if deterministic latency needed

---

## Reference

- **exchange-core** (GitHub): production matching engine on Disruptor + Agrona + Eclipse Collections. Best open-source reference.
- **LMAX Disruptor docs**: `com.lmax.disruptor` Javadoc for ring buffer, wait strategies, sequence barriers.
- **Agrona docs**: `org.agrona` for `AtomicBuffer`, `BufferUtil`, `BroadcastTransmitter`.

---

## File Layout

```
src/main/java/com/vampireteeth/trading/
├── Main.java              # entry point, wires everything
├── MatchingEngine.java    # the single-threaded handler
├── OrderBook.java         # TreeMap-based book (bids + asks)
├── Order.java             # resting order (mutable, reused)
├── OrderEvent.java        # ring buffer event (pre-allocated, reset per cycle)
└── TradeEvent.java        # downstream trade event

src/test/java/com/vampireteeth/trading/
└── OrderBookTest.java     # unit tests for price-time priority
```

---

## Build Order

| Week | Milestone |
|---|---|
| 1 | Event schema + in-heap TreeMap book + single Disruptor + unit tests for price-time priority |
| 2 | Full matching logic (new/cancel/replace, partial fills, self-trade prevention) + trade event output |
| 3 | Aeron or Netty ingress + market data distribution + WAL |
| 4 | Migrate book to Agrona off-heap + JVM/OS tuning + latency benchmarking (p99, p999) |
| 5 | Risk pipeline + snapshot/restore + chaos testing (kill -9, replay WAL) |
```

