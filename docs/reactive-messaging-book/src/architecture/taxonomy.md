# Pattern Taxonomy

The 39 EIP patterns divide into six categories. This taxonomy shows the JOTP implementation strategy for each.

## Category 1: Message Channels (01–06)

Channels are the conduits through which messages flow. They determine delivery semantics.

| # | Pattern | JOTP Class | Delivery Model |
|---|---|---|---|
| 01 | Point-to-Point | `PointToPointChannel<T>` | Exactly one consumer |
| 02 | Publish-Subscribe | `PublishSubscribeChannel<T>` | All subscribers |
| 03 | Dead Letter | `DeadLetterChannel<T>` | Capture undeliverable |
| 04 | Message Bus | `EventManager<E>` | Shared backbone |
| 05 | Durable Subscriber | `DurableSubscriber<T>` | Buffered across pause/resume |
| 06 | Datatype Channel | Typed `MessageChannel<T>` | Compile-time type safety |

## Category 2: Message Construction (07–13)

These patterns define *what is in a message* rather than how it flows.

| # | Pattern | JOTP Mechanism | Java 26 Feature |
|---|---|---|---|
| 07 | Command | `record Cmd(…)` | Record type |
| 08 | Document | `record Doc(…)` | Record type |
| 09 | Event | `EventManager<E>` | Sealed interface of records |
| 10 | Request-Reply | `proc.ask(msg)` | `CompletableFuture<S>` |
| 11 | Return Address | `CompletableFuture` in record | Embedded reply handle |
| 12 | Correlation ID | `UUID` in sealed msg | Pattern matching |
| 13 | Message Sequence | `record Seq(int n, T payload)` | Sequence number in state |

## Category 3: Message Routing (14–22)

Routing patterns determine *where* a message goes based on its content or context.

| # | Pattern | JOTP Class | Routing Mechanism |
|---|---|---|---|
| 14 | Content-Based Router | `MessageRouter<T>` | Static predicate list |
| 15 | Dynamic Router | `DynamicRouter<T>` | `CopyOnWriteArrayList` routes |
| 16 | Message Filter | `MessageFilter<T>` | Accept / dead-letter |
| 17 | Recipient List | `PublishSubscribeChannel<T>` | Fan-out to all handlers |
| 18 | Splitter | `MessageSplitter<T,U>` | Explode list → parts |
| 19 | Aggregator | `MessageAggregator<T,R>` | Correlation groups |
| 20 | Resequencer | `Resequencer<T,K>` | `Proc<State,Entry>` + `TreeMap` |
| 21 | Scatter-Gather | `Parallel.all(tasks)` | `StructuredTaskScope` |
| 22 | Process Manager | `Supervisor` + `StateMachine` | Saga orchestration |

## Category 4: Message Transformation (23–28)

Transformation patterns mutate message *content* without changing routing topology.

| # | Pattern | JOTP Class | Transformation |
|---|---|---|---|
| 23 | Message Translator | `MessageTransformer<T,U>` | `Function<T,U>` |
| 24 | Envelope Wrapper | Record wrapping | Add metadata |
| 25 | Content Enricher | `ContentEnricher<T,R,U>` | `BiFunction<T,R,U>` |
| 26 | Content Filter | `MessageFilter<T>` | Remove fields |
| 27 | Claim Check | `ConcurrentHashMap` store | Store large payload |
| 28 | Normalizer | `MessageTransformer` per source | Canonical form |

## Category 5: Message Endpoints (29–36)

Endpoints are the "edges" where applications attach to the messaging infrastructure.

| # | Pattern | JOTP Class | Endpoint Model |
|---|---|---|---|
| 29 | Polling Consumer | `PollingConsumer<T>` | `Proc` + poll loop |
| 30 | Event-Driven Consumer | `PointToPointChannel<T>` | Push-driven |
| 31 | Competing Consumers | `MessageDispatcher<T>` | Worker pool |
| 32 | Message Dispatcher | `MessageDispatcher<T>` | Round-robin / race |
| 33 | Selective Consumer | `MessageFilter<T>` + `Proc` | Predicate-based |
| 34 | Idempotent Receiver | `Proc` + `HashSet<ID>` | Dedup state |
| 35 | Service Activator | `Proc<Count,Msg>` | Method per message |
| 36 | Channel Adapter | Virtual thread bridge | External queue → channel |

## Category 6: System Management (37–39)

System patterns support observability and debugging without modifying business logic.

| # | Pattern | JOTP Class | Mechanism |
|---|---|---|---|
| 37 | Wire Tap | `WireTap<T>` | Virtual thread fork |
| 38 | Message Store | `MessageStore` | `ConcurrentHashMap` + `Proc` |
| 39 | Message History | Record envelope | Immutable history list |

## Pattern Dependencies

```
MessageChannel<T>        ← all 39 patterns depend on this
    ├── PointToPointChannel<T>
    │       └── used by: Aggregator, Dispatcher, Pipeline, etc.
    ├── PublishSubscribeChannel<T>
    │       └── EventManager<E>  ← backed by Proc<S,M>
    ├── MessageFilter<T>
    │       └── MessageRouter<T>  ← composes filters
    └── DurableSubscriber<T>
            └── Proc<State<T>,Cmd<T>>  ← full OTP semantics
```
