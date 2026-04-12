# Project Reactor in MariaAlpha: A Complete Guide

This document explains how Project Reactor works in general, and then walks through every
reactive pattern used in the MariaAlpha codebase with full code examples. It is written for
someone who understands Java and Spring Boot but has never used reactive streams before.

---

## Table of Contents

1. [What Problem Does Reactor Solve?](#1-what-problem-does-reactor-solve)
2. [Core Concepts](#2-core-concepts)
   - [Flux and Mono](#flux-and-mono)
   - [The Subscribe Contract](#the-subscribe-contract)
   - [Backpressure](#backpressure)
   - [Operators](#operators)
3. [Sinks: Bridging Imperative and Reactive Code](#3-sinks-bridging-imperative-and-reactive-code)
4. [Disposable: Lifecycle Management](#4-disposable-lifecycle-management)
5. [How MariaAlpha Uses Reactor (Production Code)](#5-how-mariaalpha-uses-reactor-production-code)
   - [The Reactive Data Pipeline](#the-reactive-data-pipeline)
   - [Layer 1: MarketDataAdapter Interface](#layer-1-marketdataadapter-interface)
   - [Layer 2a: AlpacaMarketDataAdapter (Sink-based Producer)](#layer-2a-alpacamarketdataadapter)
   - [Layer 2b: SimulatedMarketDataAdapter (Factory-based Producer)](#layer-2b-simulatedmarketdataadapter)
   - [Layer 3: TickKafkaPublisher (Terminal Subscriber)](#layer-3-tickkafkapublisher)
   - [Layer 4: OrderBookManager (Aggregator + Re-Publisher)](#layer-4-orderbookmanager)
   - [Layer 5: BookGrpcService (Reactive-to-gRPC Bridge)](#layer-5-bookgrpcservice)
6. [How MariaAlpha Uses Reactor (Test Code)](#6-how-mariaalpha-uses-reactor-test-code)
   - [StepVerifier: Declarative Stream Assertions](#stepverifier-declarative-stream-assertions)
   - [Sinks in Tests: Manual Data Injection](#sinks-in-tests-manual-data-injection)
   - [Flux.just(): Canned Responses for Mocks](#fluxjust-canned-responses-for-mocks)
7. [Common Patterns Cheat Sheet](#7-common-patterns-cheat-sheet)
8. [Gotchas and Pitfalls](#8-gotchas-and-pitfalls)

---

## 1. What Problem Does Reactor Solve?

In traditional Java, if you want to process a stream of events (e.g., market data ticks arriving
over a WebSocket), you have two options:

- **Blocking**: sit in a `while` loop calling `socket.read()`. Simple, but ties up a thread
  per connection. With 100 symbols streaming data, that's 100 threads just waiting.
- **Callbacks**: register a listener like `onMessage(msg -> ...)`. Non-blocking, but composing
  multiple callbacks (filter this, transform that, fan out to three consumers) quickly becomes
  unreadable ("callback hell").

**Project Reactor** gives you a third option: a declarative, composable pipeline that is
inherently non-blocking. You describe *what* should happen to data as it flows through a chain
of operators, and Reactor handles *when* and *on which thread* it actually happens.

```
Traditional:  WebSocket -> callback -> if/else -> another callback -> ...
Reactor:      WebSocket -> Flux -> .filter() -> .map() -> .subscribe(consumer)
```

The key insight: **nothing happens until someone subscribes**. A `Flux` is a *recipe* for
producing data, not data itself. This is called "lazy evaluation" and it's fundamental to
understanding the entire model.

---

## 2. Core Concepts

### Flux and Mono

Reactor has exactly two types:

| Type | What it represents | Analogy |
|------|--------------------|---------|
| `Flux<T>` | A stream of 0 to N items, followed by either completion or an error | `Stream<T>` but async and potentially infinite |
| `Mono<T>` | A stream of 0 or 1 item, followed by either completion or an error | `CompletableFuture<T>` but lazier |

Every reactive stream emits a sequence of **three signal types**:

```
onNext(item)      -- data signal (0 to N times)
onError(throwable) -- terminal signal (at most once, mutually exclusive with onComplete)
onComplete()       -- terminal signal (at most once, mutually exclusive with onError)
```

A concrete example:

```java
// A Flux that emits 3 items then completes
Flux<String> symbols = Flux.just("AAPL", "MSFT", "GOOGL");
// Signals: onNext("AAPL"), onNext("MSFT"), onNext("GOOGL"), onComplete()

// A Flux that emits nothing and completes immediately
Flux<String> empty = Flux.empty();
// Signals: onComplete()

// A Mono that emits one item
Mono<String> single = Mono.just("AAPL");
// Signals: onNext("AAPL"), onComplete()
```

### The Subscribe Contract

**Nothing happens until `.subscribe()` is called.** This is the most important rule in Reactor.

```java
// This does NOTHING -- it just builds a recipe
Flux<MarketTick> pipeline = adapter.streamTicks()
    .filter(tick -> tick.symbol().equals("AAPL"))
    .map(tick -> tick.price());

// THIS is where work actually begins
pipeline.subscribe(price -> System.out.println("Got price: " + price));
```

`.subscribe()` has several overloads:

```java
// 1-arg: just onNext
flux.subscribe(item -> handleItem(item));

// 2-arg: onNext + onError
flux.subscribe(
    item -> handleItem(item),
    error -> handleError(error)
);

// 3-arg: onNext + onError + onComplete
flux.subscribe(
    item -> handleItem(item),
    error -> handleError(error),
    () -> handleComplete()
);
```

The 3-arg form is used in BookGrpcService to know when the upstream Flux finishes so it can
close the gRPC stream. Missing the `onComplete` handler was the cause of the hanging
`streamBookSnapshotsEmitsUpdates` test -- the gRPC response stream never got closed.

### Backpressure

Backpressure is the mechanism by which a slow consumer tells a fast producer to slow down.
In traditional systems, if a WebSocket sends 10,000 messages/second but your consumer can
only handle 1,000, the buffer grows until you run out of memory.

Reactor solves this through a *demand* protocol: the subscriber tells the publisher how many
items it's ready to accept. This happens automatically when you use operators, but when you
bridge from imperative code (like WebSocket callbacks) into a Flux, you need to choose a
strategy. In MariaAlpha, we use:

```java
Sinks.many().multicast().onBackpressureBuffer()
```

This means: "if subscribers can't keep up, buffer the excess items in memory." For a market
data system processing ticks, this is the right default -- we don't want to drop ticks, and
the buffer acts as a shock absorber for momentary bursts.

### Operators

Operators are methods on `Flux`/`Mono` that transform the stream. They return a new
`Flux`/`Mono` (they never mutate the original). Here are the ones used in MariaAlpha:

| Operator | What it does | MariaAlpha usage |
|----------|-------------|------------------|
| `.filter(predicate)` | Drops items that don't match | `OrderBookManager.streamSnapshots()` filters by symbol set |
| `.map(fn)` | Transforms each item 1:1 | `AlpacaMarketDataAdapter` maps WebSocket payloads to text |
| `.take(n)` | Emits the first N items then completes | Tests limit infinite streams to a finite count |
| `.next()` | Takes only the first item, returns a `Mono` | Tests that only need to check one emission |
| `.doOnNext(fn)` | Side-effect on each item (doesn't transform) | `AlpacaMarketDataAdapter` logs/handles each message |
| `.doOnError(fn)` | Side-effect on error (doesn't recover) | `AlpacaMarketDataAdapter` logs WebSocket errors |
| `.doOnTerminate(fn)` | Side-effect on completion or error | `AlpacaMarketDataAdapter` logs WebSocket close |
| `.then()` | Ignores all data signals, just propagates completion/error | `AlpacaMarketDataAdapter` chains WebSocket receive |

A key principle: operators like `.doOnNext()` are for **side effects** (logging, metrics) and
don't change the stream's data. Operators like `.map()` and `.filter()` **transform** the data.

---

## 3. Sinks: Bridging Imperative and Reactive Code

This is the most important pattern in MariaAlpha's reactive architecture.

**Problem**: You have imperative, callback-driven code (a WebSocket handler that calls
`handleTrade(node)` whenever a trade arrives), but you want to expose the data as a
`Flux<MarketTick>` so downstream consumers can use reactive operators.

**Solution**: `Sinks.Many<T>` -- a programmatic handle that lets you *push* items into a
Flux from imperative code.

```java
// Create the sink (once, usually in constructor)
private final Sinks.Many<MarketTick> tickSink =
    Sinks.many()        // we want to emit multiple items
    .multicast()         // multiple subscribers can each see all items
    .onBackpressureBuffer();  // buffer if subscribers are slow

// Push data in from imperative code (called from WebSocket handler, etc.)
tickSink.tryEmitNext(tick);      // push one item
tickSink.tryEmitComplete();      // signal "no more data"

// Expose as Flux for reactive consumers
public Flux<MarketTick> streamTicks() {
    return tickSink.asFlux();    // returns a Flux backed by this sink
}
```

Think of a Sink as a pipe:
- **Write end** (`tryEmitNext`): imperative code pushes data in
- **Read end** (`asFlux()`): reactive subscribers pull data out

The `tryEmit*` methods return an `EmitResult` that tells you if the emission succeeded. In
MariaAlpha we use the fire-and-forget `tryEmitNext()` (ignoring the result) because the
`onBackpressureBuffer()` strategy means it will almost always succeed.

### Sink Flavors

```java
// UNICAST: exactly one subscriber allowed
Sinks.many().unicast().onBackpressureBuffer()

// MULTICAST: multiple subscribers, each sees items emitted AFTER they subscribe
Sinks.many().multicast().onBackpressureBuffer()

// REPLAY: multiple subscribers, new subscribers see some/all past items
Sinks.many().replay().all()       // replay everything
Sinks.many().replay().limit(10)   // replay last 10
```

MariaAlpha uses **multicast** everywhere because multiple consumers (TickKafkaPublisher,
OrderBookManager, and potentially others) all need to see the same tick stream, and they
subscribe at startup so they don't need replay.

---

## 4. Disposable: Lifecycle Management

When you call `.subscribe()`, it returns a `Disposable` -- a handle to cancel the subscription.

```java
Disposable subscription = flux.subscribe(item -> process(item));

// Later, to stop receiving items:
subscription.dispose();

// Check if already disposed:
subscription.isDisposed();
```

In MariaAlpha, every component that subscribes to a Flux stores the `Disposable` as a field
and calls `.dispose()` during shutdown:

```java
@Component
public class SomeComponent {
    private volatile Disposable subscription;  // volatile: may be read/written by different threads

    @PostConstruct
    void start() {
        subscription = someFlux.subscribe(this::handleItem);
    }

    @PreDestroy
    void stop() {
        if (subscription != null && !subscription.isDisposed()) {
            subscription.dispose();
        }
    }
}
```

This pattern appears in:
- `TickKafkaPublisher` (disposes its subscription to `adapter.streamTicks()`)
- `OrderBookManager` (disposes its subscription to `adapter.streamTicks()`)
- `AlpacaMarketDataAdapter` (disposes its WebSocket connection)
- `BookGrpcService` (disposes when gRPC client cancels)

The `volatile` keyword on the field is important: the subscription is created on the main
thread (`@PostConstruct`) but may be disposed from a different thread (`@PreDestroy` or a
gRPC cancellation callback).

---

## 5. How MariaAlpha Uses Reactor (Production Code)

### The Reactive Data Pipeline

Here is the complete data flow through the system:

```
Alpaca WebSocket           CSV File
      |                       |
      v                       v
AlpacaMarketDataAdapter   SimulatedMarketDataAdapter
(Sinks.Many)              (Flux.create / Flux.fromIterable)
      |                       |
      +-------+-------+-------+
              |
              v
        Flux<MarketTick>       <-- MarketDataAdapter.streamTicks()
              |
     +--------+--------+
     |                  |
     v                  v
TickKafkaPublisher   OrderBookManager
(.subscribe)         (.subscribe + Sinks.Many)
     |                  |
     v                  v
  Kafka topic      Flux<OrderBookEntry>
                        |
                        v
                  BookGrpcService
                  (.subscribe with 3 handlers)
                        |
                        v
                   gRPC stream to clients
```

Every arrow represents a reactive subscription. The data flows from top to bottom,
driven by the producers at the top pushing items through the pipeline.

### Layer 1: MarketDataAdapter Interface

**File:** `market-data-gateway/src/main/java/.../adapter/MarketDataAdapter.java`

```java
public interface MarketDataAdapter {
    void connect(List<String> symbols);
    void disconnect();
    Flux<MarketTick> streamTicks();   // <-- the reactive contract
    List<HistoricalBar> getHistoricalBars(...);
}
```

The `streamTicks()` method is the foundation. It returns a `Flux<MarketTick>` -- an
asynchronous stream of normalized market data ticks. Any component that wants live market
data subscribes to this Flux.

Note the mix of imperative (`connect`/`disconnect`) and reactive (`streamTicks`) methods.
This is a common pattern: lifecycle is managed imperatively (Spring calls `connect` at startup),
but data flows reactively.

### Layer 2a: AlpacaMarketDataAdapter

**File:** `market-data-gateway/src/main/java/.../adapter/alpaca/AlpacaMarketDataAdapter.java`

This is the most interesting reactive component because it bridges a WebSocket (imperative
callbacks) to a Flux (reactive stream).

**Constructor -- creating the sink:**
```java
// Bridge imperative (WebSocket callback pushes data) to reactive
// (downstream consumers subscribe to a Flux)
this.tickSink = Sinks.many().multicast().onBackpressureBuffer();
```

**connect() -- subscribing to the WebSocket:**
```java
wsConnection = client
    .execute(uri, session ->
        session
            .receive()                                    // Flux<WebSocketMessage>
            .map(WebSocketMessage::getPayloadAsText)      // Flux<String>
            .doOnNext(msg -> handleMessage(msg, session, symbols))  // side-effect: parse & handle
            .then())                                      // Mono<Void> (ignore data, just propagate completion)
    .doOnError(err -> LOG.error("WebSocket error", err))  // side-effect: log errors
    .doOnTerminate(() -> LOG.info("WebSocket connection closed"))
    .subscribe();                                         // start the pipeline, get a Disposable
```

Let's unpack this chain step by step:

1. `session.receive()` -- Spring's reactive WebSocket client gives us a `Flux<WebSocketMessage>`
   that emits one item for every message the server sends.
2. `.map(WebSocketMessage::getPayloadAsText)` -- transforms each `WebSocketMessage` into a
   `String` containing the JSON payload. This is a 1:1 transformation.
3. `.doOnNext(msg -> handleMessage(...))` -- for each JSON string, call `handleMessage`.
   This is a *side-effect* operator: it doesn't transform the stream, it just does something
   with each item. Inside `handleMessage`, trade/quote data gets parsed and pushed into the
   sink via `tickSink.tryEmitNext(tick)`.
4. `.then()` -- discards all the actual data items and returns a `Mono<Void>` that only
   carries the completion/error signal. This is needed because `client.execute()` expects
   the session handler to return a `Publisher<Void>` representing "when are you done?".
5. `.doOnError(...)` / `.doOnTerminate(...)` -- logging side-effects.
6. `.subscribe()` -- activates the entire pipeline and returns a `Disposable`.

**handleTrade() / handleQuote() -- pushing data into the sink:**
```java
private void handleTrade(JsonNode node) throws JsonProcessingException {
    var trade = objectMapper.treeToValue(node, AlpacaTrade.class);
    var tick = new MarketTick(trade.symbol(), trade.timestamp(), EventType.TRADE, ...);
    tickSink.tryEmitNext(tick);  // push into the reactive pipeline
}
```

This is the bridge point: imperative code (a method called from a JSON parser) pushes
a `MarketTick` into the sink, which immediately delivers it to all Flux subscribers.

**streamTicks() -- exposing the read end:**
```java
public Flux<MarketTick> streamTicks() {
    return tickSink.asFlux();  // hand out the Flux backed by our sink
}
```

**disconnect() -- signaling completion:**
```java
public void disconnect() {
    if (wsConnection != null && !wsConnection.isDisposed()) {
        wsConnection.dispose();        // stop the WebSocket subscription
    }
    tickSink.tryEmitComplete();        // tell all Flux subscribers: no more data
}
```

**Sending messages to the WebSocket:**
```java
session.send(Flux.just(session.textMessage(authMsg))).subscribe();
```

`session.send()` expects a `Publisher<WebSocketMessage>` -- a reactive stream of messages
to send. `Flux.just(msg)` wraps a single message in a one-item Flux. The `.subscribe()`
at the end triggers the actual send. This is a common pattern for wrapping a single
imperative action in reactive API terms.

### Layer 2b: SimulatedMarketDataAdapter

**File:** `market-data-gateway/src/main/java/.../adapter/SimulatedMarketDataAdapter.java`

This adapter demonstrates three different ways to create a Flux:

**1. `Flux.empty()` -- an immediately-completing stream:**
```java
public Flux<MarketTick> streamTicks() {
    if (!connected || ticks == null) {
        return Flux.empty();     // emits: onComplete() immediately
    }
    // ...
}
```

Used when there's no data to emit (not connected, or no matching symbols).

**2. `Flux.fromIterable()` -- wrapping a collection:**
```java
return Flux.fromIterable(filtered);
// If filtered = [tick1, tick2, tick3], emits:
//   onNext(tick1), onNext(tick2), onNext(tick3), onComplete()
```

Converts a `List<MarketTick>` into a Flux that emits each item then completes.
Fast -- no delays between items.

**3. `Flux.create()` -- full control with a sink:**
```java
private Flux<MarketTick> replayWithDelay(List<MarketTick> filtered) {
    return Flux.create(sink -> {
        for (var i = 0; i < filtered.size(); i++) {
            if (sink.isCancelled() || !connected) {
                break;                          // respect cancellation
            }
            sink.next(filtered.get(i));         // emit one tick
            sleepBetweenTicks(filtered, i);     // wait before next
        }
        sink.complete();                        // signal end of stream
    });
}
```

`Flux.create()` gives you a `FluxSink` that you control entirely. You call `sink.next(item)`
to emit items and `sink.complete()` when done. The code inside the lambda runs when someone
subscribes to this Flux.

Key difference from `Sinks.Many`:
- `Sinks.Many` is created once and lives as a field -- multiple calls to `tryEmitNext` over time
- `Flux.create()` runs its lambda fresh for each subscriber -- good for one-shot sequences

### Layer 3: TickKafkaPublisher

**File:** `market-data-gateway/src/main/java/.../publisher/TickKafkaPublisher.java`

This is the simplest reactive pattern: a **terminal subscriber** that just consumes data.

```java
@PostConstruct
void start() {
    subscription = adapter.streamTicks().subscribe(this::publishTick);
}
```

One line does everything:
1. `adapter.streamTicks()` -- get the `Flux<MarketTick>` from whichever adapter is active
2. `.subscribe(this::publishTick)` -- for each tick, call `publishTick` (which serializes
   to JSON and sends to Kafka)
3. Store the returned `Disposable` so we can cancel on shutdown

There are no reactive operators here -- it's just "give me every tick, I'll handle it
imperatively in `publishTick`." This is perfectly fine. Not everything needs to be a
chain of operators.

### Layer 4: OrderBookManager

**File:** `market-data-gateway/src/main/java/.../book/OrderBookManager.java`

This component is both a **subscriber** (consuming ticks) and a **publisher** (producing
book updates). It's the reactive equivalent of a "processor" -- data goes in, transformed
data comes out.

**Consuming upstream ticks:**
```java
@PostConstruct
void start() {
    subscription = adapter.streamTicks().subscribe(this::onTick);
}
```

Same pattern as TickKafkaPublisher.

**Processing and re-publishing:**
```java
private final Sinks.Many<OrderBookEntry> updateSink =
    Sinks.many().multicast().onBackpressureBuffer();

void onTick(MarketTick tick) {
    var updated = books.compute(tick.symbol(), (symbol, current) -> {
        if (current == null) {
            current = OrderBookEntry.empty(symbol);
        }
        return current.update(tick);
    });
    updateSink.tryEmitNext(updated);  // publish the updated book entry
}
```

After updating the in-memory book (imperatively, via `ConcurrentHashMap.compute`), it
pushes the result into a *second* sink. This creates a new `Flux<OrderBookEntry>` that
downstream consumers can subscribe to.

**Exposing filtered streams:**
```java
public Flux<OrderBookEntry> streamSnapshots(Set<String> symbols) {
    return updateSink.asFlux().filter(entry -> symbols.contains(entry.symbol()));
}
```

The `.filter()` operator drops entries that don't match the requested symbols. Each
subscriber gets their own filtered view of the same underlying stream.

### Layer 5: BookGrpcService

**File:** `market-data-gateway/src/main/java/.../grpc/BookGrpcService.java`

This is the most complex reactive pattern: bridging from Reactor's `Flux` to gRPC's
`StreamObserver` (a completely different streaming abstraction).

```java
public void streamBookSnapshots(
    StreamBookSnapshotsRequest request,
    StreamObserver<BookSnapshotResponse> responseObserver) {

    var symbols = new HashSet<>(request.getSymbolsList());
    var serverObserver = (ServerCallStreamObserver<BookSnapshotResponse>) responseObserver;

    Disposable subscription = orderBookManager
        .streamSnapshots(symbols)              // Flux<OrderBookEntry>
        .subscribe(
            // onNext: for each book update, send it to the gRPC client
            entry -> {
                synchronized (responseObserver) {
                    if (!serverObserver.isCancelled()) {
                        responseObserver.onNext(toProto(entry));
                    }
                }
            },
            // onError: forward errors to the gRPC client
            responseObserver::onError,
            // onComplete: close the gRPC stream
            responseObserver::onCompleted
        );

    // If the gRPC client disconnects, cancel the Flux subscription
    serverObserver.setOnCancelHandler(subscription::dispose);
}
```

Three critical details:

1. **Three-argument subscribe**: All three handlers (onNext, onError, onComplete) are
   provided. The `onComplete` handler calls `responseObserver.onCompleted()` to close the
   gRPC stream when the Flux finishes. Without this, the gRPC stream stays open forever.

2. **synchronized block**: The `responseObserver` is not thread-safe -- if multiple threads
   call `onNext` concurrently, the gRPC stream can get corrupted. The `synchronized` block
   ensures only one thread writes at a time. This is necessary because Reactor may deliver
   items on different threads.

3. **Cancellation bridge**: `setOnCancelHandler(subscription::dispose)` -- when the gRPC
   client disconnects (cancels the stream), we dispose the Reactor subscription so we stop
   processing data for a client that's no longer listening.

---

## 6. How MariaAlpha Uses Reactor (Test Code)

### StepVerifier: Declarative Stream Assertions

`StepVerifier` is Reactor's testing tool. It lets you subscribe to a Flux/Mono and assert
exactly what signals should arrive, in what order.

**Basic pattern:**
```java
StepVerifier.create(someFlux)     // wrap the Flux
    .expectNext("AAPL")           // first item should be "AAPL"
    .expectNext("MSFT")           // second item should be "MSFT"
    .verifyComplete();            // then it should complete (blocks until done)
```

**Used in SimulatedMarketDataAdapterTest:**

```java
// Assert exact count of emissions
StepVerifier.create(adapter.streamTicks())
    .expectNextCount(5)
    .verifyComplete();

// Assert items in order using .map() to extract a field
StepVerifier.create(adapter.streamTicks().map(MarketTick::symbol))
    .expectNext("AAPL", "AAPL", "AAPL", "MSFT", "MSFT")
    .verifyComplete();

// Assert a condition holds for ALL items
StepVerifier.create(adapter.streamTicks())
    .thenConsumeWhile(tick -> tick.source() == DataSource.SIMULATED)
    .verifyComplete();

// Assert detailed properties of a single item (using .next() to take just one)
StepVerifier.create(adapter.streamTicks().next())
    .assertNext(tick -> {
        assertThat(tick.symbol()).isEqualTo("AAPL");
        assertThat(tick.price()).isEqualByComparingTo(new BigDecimal("178.50"));
        // ...more assertions...
    })
    .verifyComplete();

// Assert an empty stream (no items, just completion)
StepVerifier.create(adapter.streamTicks())
    .verifyComplete();
```

**Used in AlpacaMarketDataAdapterTest:**

```java
// .take(n): limit an infinite-ish stream to N items for testing
StepVerifier.create(adapter.streamTicks().take(2))
    .assertNext(tick -> assertThat(tick.symbol()).isEqualTo("AAPL"))
    .assertNext(tick -> assertThat(tick.symbol()).isEqualTo("MSFT"))
    .verifyComplete();
```

**Used in OrderBookManagerTest -- the `.then()` pattern for reactive testing:**

```java
StepVerifier.create(flux.take(2))
    // .then() executes an action DURING verification (after subscription but before items)
    .then(() -> tickSink.tryEmitNext(tradeTick("AAPL", "178.52", 100, 1000000)))
    .then(() -> tickSink.tryEmitNext(quoteTick("AAPL", "178.50", "178.54", 200, 150)))
    .assertNext(entry ->
        assertThat(entry.lastPrice()).isEqualByComparingTo(new BigDecimal("178.52")))
    .assertNext(entry ->
        assertThat(entry.bidPrice()).isEqualByComparingTo(new BigDecimal("178.50")))
    .verifyComplete();
```

This is subtle and important. The sequence is:

1. `StepVerifier.create(flux.take(2))` -- subscribe to the flux (which starts empty)
2. `.then(() -> tickSink.tryEmitNext(...))` -- push a tick into the sink, which flows
   through OrderBookManager and arrives as an OrderBookEntry
3. `.assertNext(...)` -- assert the first emitted OrderBookEntry
4. `.then(() -> tickSink.tryEmitNext(...))` -- push a second tick
5. `.assertNext(...)` -- assert the second emitted OrderBookEntry
6. `.verifyComplete()` -- `.take(2)` limits the stream to 2 items, so it completes here

The `.then()` steps let you **interleave actions with assertions**, which is essential
when testing reactive pipelines that respond to external input.

### Sinks in Tests: Manual Data Injection

The most common test pattern in MariaAlpha is using a `Sinks.Many` to control exactly
what data enters the pipeline:

```java
// In the test class:
private final Sinks.Many<MarketTick> tickSink =
    Sinks.many().multicast().onBackpressureBuffer();

@BeforeEach
void setUp() {
    var adapter = mock(MarketDataAdapter.class);
    when(adapter.streamTicks()).thenReturn(tickSink.asFlux());  // mock returns our sink's Flux
    manager = new OrderBookManager(adapter);
    manager.start();  // subscribes to the Flux
}

@Test
void tradeTickUpdatesBook() {
    // Push a tick into the pipeline
    tickSink.tryEmitNext(tradeTick("AAPL", "178.52", 100, 1000000));

    // Assert the result
    var snapshot = manager.getSnapshot("AAPL");
    assertThat(snapshot.lastPrice()).isEqualByComparingTo(new BigDecimal("178.52"));
}
```

This pattern is used in:
- `TickKafkaPublisherTest` -- inject ticks, verify Kafka sends
- `TickKafkaPublisherIntegrationTest` -- inject ticks, verify real Kafka messages
- `OrderBookManagerTest` -- inject ticks, verify book state and streaming output

It works because Sinks deliver items synchronously to subscribers on the same thread by
default, so by the time `tryEmitNext()` returns, the subscriber callback has already
executed.

### Flux.just(): Canned Responses for Mocks

In `BookGrpcServiceTest`, the mock returns a pre-built Flux:

```java
when(orderBookManager.streamSnapshots(any()))
    .thenReturn(Flux.just(entry1, entry2));
```

`Flux.just(entry1, entry2)` creates a Flux that emits exactly those two items then completes.
This is ideal for mocking because it's deterministic -- no timing, no threading, no sinks
needed. The two items emit immediately when subscribed.

---

## 7. Common Patterns Cheat Sheet

### Creating a Flux

```java
Flux.empty()                          // 0 items, completes immediately
Flux.just(a, b, c)                    // fixed items, completes after last
Flux.fromIterable(list)               // items from a collection
Flux.create(sink -> { ... })          // full imperative control
sinks.many().multicast()              // long-lived sink for bridging
    .onBackpressureBuffer()
    .asFlux()
```

### Transforming a Flux

```java
flux.map(item -> transform(item))     // 1:1 transformation
flux.filter(item -> condition(item))  // drop items that don't match
flux.take(n)                          // only first N items
flux.next()                           // Flux -> Mono (first item only)
```

### Side Effects (don't transform the stream)

```java
flux.doOnNext(item -> log(item))      // run action on each item
flux.doOnError(err -> log(err))       // run action on error
flux.doOnTerminate(() -> cleanup())   // run action on complete or error
flux.doOnCancel(() -> cleanup())      // run action if subscriber cancels
```

### Subscribing

```java
// Simple: just handle items
Disposable d = flux.subscribe(item -> process(item));

// Full: handle items, errors, and completion
Disposable d = flux.subscribe(
    item -> process(item),
    error -> handleError(error),
    () -> handleComplete()
);

// Cancel later
d.dispose();
```

### Testing

```java
// Assert exact sequence
StepVerifier.create(flux)
    .expectNext(item1, item2)
    .verifyComplete();

// Assert count
StepVerifier.create(flux)
    .expectNextCount(5)
    .verifyComplete();

// Assert with custom logic
StepVerifier.create(flux)
    .assertNext(item -> assertThat(item.name()).isEqualTo("foo"))
    .verifyComplete();

// Inject data during test
StepVerifier.create(flux)
    .then(() -> sink.tryEmitNext(testData))
    .assertNext(result -> assertThat(result).isNotNull())
    .verifyComplete();

// Assert empty stream
StepVerifier.create(flux)
    .verifyComplete();

// Assert error
StepVerifier.create(flux)
    .verifyError(SomeException.class);
```

---

## 8. Gotchas and Pitfalls

### 1. Nothing Happens Until You Subscribe

The most common Reactor mistake. If you build a pipeline but forget to subscribe, nothing
executes:

```java
// BUG: this does nothing!
adapter.streamTicks().filter(t -> t.symbol().equals("AAPL"));

// FIX: subscribe to activate the pipeline
adapter.streamTicks().filter(t -> t.symbol().equals("AAPL")).subscribe(this::handle);
```

### 2. Missing onComplete in 3-arg Subscribe

If you use the 2-arg subscribe (onNext + onError) on a finite Flux, the completion signal
is silently ignored. This is fine for infinite streams (like a live WebSocket feed), but
causes hangs when the stream is expected to finish:

```java
// BUG: if the Flux completes, responseObserver never gets onCompleted()
flux.subscribe(
    item -> responseObserver.onNext(item),
    responseObserver::onError
);

// FIX: add the third argument
flux.subscribe(
    item -> responseObserver.onNext(item),
    responseObserver::onError,
    responseObserver::onCompleted    // <-- this was the hanging test fix
);
```

### 3. Thread Safety of Downstream Consumers

Reactor may deliver items on different threads (especially with `Sinks.Many`). If your
subscriber writes to a non-thread-safe object, you need synchronization:

```java
// BUG: gRPC StreamObserver is not thread-safe
flux.subscribe(entry -> responseObserver.onNext(toProto(entry)));

// FIX: synchronize
flux.subscribe(entry -> {
    synchronized (responseObserver) {
        responseObserver.onNext(toProto(entry));
    }
});
```

### 4. Sinks.Many tryEmitNext Can Fail

`tryEmitNext()` returns an `EmitResult` that can be `FAIL_*`. In MariaAlpha we ignore
the result (acceptable for our backpressure-buffered sinks), but in other contexts you
might want to check:

```java
var result = sink.tryEmitNext(item);
if (result.isFailure()) {
    LOG.warn("Failed to emit: {}", result);
}
```

### 5. Blocking Inside a Reactive Chain

Never block (Thread.sleep, blocking I/O) inside a `.map()`, `.filter()`, or `.flatMap()`.
This ties up the reactive scheduler thread and can deadlock the pipeline.

```java
// BUG: blocking inside a reactive operator
flux.map(item -> {
    var result = httpClient.get(url);  // blocking HTTP call!
    return transform(result);
});

// OK: blocking inside Flux.create() is acceptable (runs on subscriber's thread)
Flux.create(sink -> {
    Thread.sleep(100);  // this is fine here
    sink.next(item);
});
```

The `SimulatedMarketDataAdapter.replayWithDelay()` uses `Thread.sleep` inside `Flux.create()`
which is acceptable because `Flux.create` runs on the subscribing thread by default.

### 6. Hot vs Cold Flux

- **Cold Flux**: replays data for each new subscriber (like `Flux.just()`, `Flux.fromIterable()`)
- **Hot Flux**: emits data regardless of subscribers; late subscribers miss earlier items
  (like `Sinks.Many.multicast().asFlux()`)

In MariaAlpha:
- `SimulatedMarketDataAdapter.streamTicks()` with `Flux.fromIterable` is **cold** -- each
  subscriber gets all ticks from the beginning
- `AlpacaMarketDataAdapter.streamTicks()` with `tickSink.asFlux()` is **hot** -- subscribers
  only see ticks that arrive after they subscribe
- `OrderBookManager.streamSnapshots()` with `updateSink.asFlux()` is **hot** -- same reason

This distinction matters for testing: with a hot Flux, you must subscribe *before* emitting
data (which is why `OrderBookManagerTest` uses `StepVerifier.create(flux).then(() -> emit)...`
rather than emitting first and subscribing second).
