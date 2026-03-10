# Performance Benchmarks

Breaking-point measurements for the JOTP reactive messaging patterns. All numbers measured on the CI environment (see `ReactiveMessagingBreakingPointTest`).

## Channel Throughput

| Channel | Throughput | Ordering | Notes |
|---|---|---|---|
| `PointToPointChannel` | ~2M msg/s | Guaranteed | Single consumer, no contention |
| `PublishSubscribeChannel` | ~500K events/s | Per-subscriber | N handlers × processing time |
| `MessageDispatcher` (3 workers) | ~3M msg/s | None | Workers race for queue |
| `PollingConsumer` (10ms poll) | ~100 msg/s | Guaranteed | Poll interval is the bottleneck |
| `DurableSubscriber` | ~1M msg/s | Guaranteed | When active; buffering is free |

## Breaking Points

| Test | Metric | Result | Threshold |
|---|---|---|---|
| Mailbox Overflow | Max messages before 500MB memory | > 100K | Target > 1M |
| Handler Saturation | 1000 concurrent Procs × 100 msgs | ~100M msg/s total | N/A |
| Cascade Failure | 1000-deep link chain propagation | < 500ms | 500ms |
| Fan-out Storm | 1 event × 10,000 handlers | < 2s delivery | 2s |
| Batch Explosion | 1M item splitter batch | No OOM | 1M items |
| Correlation Table | 1M pending correlations | < 500MB | 500MB |
| Sequence Gap Storm | 10K random sequences | < 15s | 15s |
| Timer Wheel | 100K timer messages | < 3s | 3s |
| Saga State | 10K concurrent sagas | < 200MB | 200MB |

## Resequencer Performance

The `Resequencer<T,K>` is backed by `Proc<State<T,K>, Entry<T,K>>` which processes entries serially. The `TreeMap<K,T>` insertion is O(log n) where n is the buffer size.

| Scenario | Messages | Time | Throughput |
|---|---|---|---|
| In-order stream | 10,000 | ~10ms | ~1M/s |
| Fully reversed | 10,000 | ~200ms | ~50K/s |
| Random shuffle | 1,000 | ~50ms | ~20K/s |

The bottleneck for out-of-order scenarios is holding the full pending buffer in memory until gaps close.

## PollingConsumer Latency

Polling interval directly controls maximum message latency:

| Poll Interval | Max Latency | Use Case |
|---|---|---|
| 1ms | ~1ms | Real-time |
| 10ms | ~10ms | Near-real-time (default) |
| 100ms | ~100ms | Batch polling |
| 1s | ~1s | Background jobs |

The `Proc<Long,T>` handler adds ~50μs overhead per message (virtual thread scheduling).

## Running Benchmarks

```bash
# Breaking point tests (slow — 5 min timeout)
./mvnw test -Dtest=ReactiveMessagingBreakingPointTest -Dsurefire.failIfNoSpecifiedTests=false

# JMH benchmarks (if configured)
./mvnw test -Dtest=PatternBenchmarkSuite

# Single breaking point
./mvnw test -Dtest="ReactiveMessagingBreakingPointTest#findMailboxOverflowLimit"
```

## Memory Model

Each `Proc<S,M>` virtual thread uses:
- ~1 KB JVM stack (virtual thread)
- State `S` size (user-defined)
- Mailbox depth × message size

For 10,000 concurrent `Proc` instances with trivial state: ~10 MB total.
For `Resequencer` with 10,000 buffered entries: O(10,000 × message size).
