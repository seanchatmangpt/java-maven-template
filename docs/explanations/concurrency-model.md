# Explanations: Concurrency Model

How JOTP uses virtual threads, structured concurrency, and message passing.

## Overview

JOTP's concurrency model rests on three Java 26 features:
1. **Virtual Threads (JEP 444)** — Lightweight process implementation
2. **Structured Concurrency (JEP 453)** — Task scope management
3. **Message Passing** — Async communication between processes

> **Status:** Coming Soon — Detailed explanation of virtual thread scheduling, message queue internals, and backpressure handling
>
> **See Also:**
> - [Architecture Overview](architecture-overview.md) — System design
> - [Design Decisions](design-decisions.md) — Why JOTP chose this model
> - [Tutorial: Virtual Threads](../tutorials/03-virtual-threads.md) — Hands-on introduction

## Quick Concepts

### Virtual Threads vs. Platform Threads

```
Platform Thread (OS-managed)
├─ ~1 MB stack
├─ High context switch cost
└─ Limited scalability (~10K per machine)

Virtual Thread (JVM-managed)
├─ ~100 KB stack (on-demand)
├─ Cheap context switch
└─ Massive scalability (1M+ per machine)
```

### Message Passing Model

```
Process A              Process B
  │                      │
  ├─────send(msg)───────►│
  │                  queue│
  │                      ├─handler(state, msg)
  │                      └─new state
```

Every JOTP process:
1. Maintains isolated state
2. Receives messages via FIFO queue
3. Processes one message at a time (sequentially)
4. Never shares mutable state with other processes

## Topics Covered (Coming Soon)

- Virtual thread scheduling and pinning
- Message queue implementation (lock-free, bounded)
- Memory model and visibility guarantees
- Backpressure and flow control
- System tuning (thread pool size, queue capacity)
- Latency analysis

---

**Previous:** [OTP Equivalence](otp-equivalence.md) | **Next:** [Design Decisions](design-decisions.md)
