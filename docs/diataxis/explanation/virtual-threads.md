# Virtual Threads as Erlang Processes

The most fundamental architectural choice in this library — and the one that makes everything else possible — is mapping each Erlang process to a Java virtual thread. Understanding why this mapping works, what it preserves, and where it differs from the Erlang original is essential for building a correct mental model of the system.

---

## What an Erlang process actually is

When Erlang documentation talks about "processes," it does not mean OS processes. Erlang processes are green threads — lightweight units of computation managed entirely within the BEAM virtual machine, invisible to the operating system.

Each Erlang process has exactly four things:

1. **A private heap** — memory that is never shared with other processes, collected independently
2. **A mailbox** — an ordered queue of messages sent from other processes
3. **A process identifier (Pid)** — an opaque reference that can be passed around and used to send messages
4. **A call stack** — the execution context of the currently running code

These four things together produce the key property: **complete isolation**. One process cannot read or modify another process's heap. The only interaction between processes is explicit message passing through mailboxes. If a process crashes — throws an exception, hits a timeout, enters an infinite loop — the crash is contained within that process. Its heap is collected. Its mailbox is abandoned. Other processes continue unaffected.

BEAM can run millions of such processes concurrently. Processes are cheap to create (a few hundred bytes each), cheap to schedule (BEAM uses preemptive scheduling with reduction counting), and cheap to destroy. The design explicitly encourages creating one process per logical activity rather than sharing processes across activities.

---

## Why Java's historical model did not map

Before Java 21, Java had one kind of thread: the platform thread, which maps 1:1 to an OS thread. OS threads are expensive: typically one to eight megabytes of stack space each, OS scheduler involvement on every context switch, and practical limits of a few thousand per JVM before performance degrades.

This expense forced Java concurrency into thread pools — shared pools of a fixed number of threads, with work submitted as tasks. Thread pools are excellent for parallelizing CPU-bound work. They are a poor model for isolated concurrent activities, for two reasons:

**First, shared execution context.** When multiple tasks run on the same thread sequentially, they share `ThreadLocal` state. A task that stores something in a `ThreadLocal` can see values stored by previous tasks on the same thread. This makes thread pool threads fundamentally non-isolated.

**Second, no per-task lifecycle.** A thread pool task is a `Runnable` submitted to a queue. It has no identity, no mailbox, and no supervisor relationship. When it fails, the `Future` holding its result gets an exception, but the pool continues. There is no structure for supervised restart.

The practical consequence was that Java developers either accepted shared mutable state (and its associated complexity) or moved to asynchronous callback chains (`CompletableFuture`, reactive streams), which eliminated thread-per-task overhead at the cost of readable code and reasonable stack traces.

---

## Virtual threads: the missing piece

Java 21 introduced virtual threads as a stable feature, and Java 26 continues their development. Virtual threads are JVM-managed threads that are multiplexed onto a small pool of platform threads (called carrier threads). From the programmer's perspective, a virtual thread is a thread — it has a stack, it can block, it participates in thread-local storage. From the JVM's perspective, it is a heap-allocated object that can be parked and unparked by the JVM scheduler when it blocks.

The key consequence: **blocking a virtual thread does not block its carrier thread.** When a virtual thread calls `LockSupport.park()`, `Thread.sleep()`, or blocks on I/O, the JVM detects this, saves the virtual thread's stack to the heap, and frees the carrier thread to run another virtual thread. When the blocking condition resolves, the virtual thread is rescheduled on any available carrier thread.

This means:

- You can create hundreds of thousands or millions of virtual threads per JVM
- Each virtual thread can block freely without starving other virtual threads
- The programming model is synchronous and readable — no callbacks, no reactive operators
- Stack traces are meaningful — the call stack at the time of a failure reflects your application logic, not framework internals

This is precisely the property that makes the Erlang process mapping possible. Each `Proc<S,M>` in this library runs its event loop on a single dedicated virtual thread. The loop blocks on the mailbox queue waiting for a message, processes it, updates state, and loops. Blocking on the mailbox is cheap because it parks the virtual thread without consuming a carrier thread.

---

## The Proc<S,M> event loop

The core of the OTP process model in this library is `Proc<S,M>`, which corresponds to Erlang's `spawn/3` combined with the `gen_server` behavior.

The type parameters reveal the design:

- `S` is the process **state** — an immutable value (typically a record) that represents everything the process needs to know to handle the next message
- `M` is the **message** type — typically a sealed interface with record subtypes

When a `Proc<S,M>` is created, it starts a virtual thread running an event loop that looks conceptually like this:

```java
// Conceptual illustration — not the actual implementation
S state = initialState;
while (running) {
    M message = mailbox.take();          // blocks until a message arrives
    state = handler.apply(state, message); // pure function: state × message → state
}
```

The handler is a **pure function** — it takes the current state and a message, and returns the new state. It does not perform side effects (other than sending messages to other processes). This purity means that the state transition is deterministic, testable, and easy to reason about.

In Erlang, the equivalent is the `gen_server:handle_call/3` and `handle_cast/2` callbacks, which take the current state and return a new state and a reply. The structural correspondence is exact.

---

## The mailbox: LinkedTransferQueue

Erlang process mailboxes have specific semantics: messages are delivered in the order sent, and a process can selectively receive messages by pattern matching, leaving non-matching messages in the mailbox for later.

This library uses `java.util.concurrent.LinkedTransferQueue<M>` as the mailbox implementation. `LinkedTransferQueue` is:

- **Unbounded** — no producer is ever blocked by the queue (unlike `ArrayBlockingQueue`)
- **Lock-free** for common cases — high throughput for many producers
- **FIFO** — messages arrive in the order they were sent

The selective receive pattern from Erlang (`receive ... after` with pattern guards) is expressed differently in Java: because messages are typed (sealed interfaces), you perform exhaustive pattern matching in the handler, and the type system ensures all message variants are handled. You cannot defer handling of a message variant until later by leaving it in the queue, but in practice this Erlang feature is used primarily for request/reply correlation, which is handled by `Proc.ask()` using a type-safe correlation mechanism.

---

## ProcRef: the stable process handle

In Erlang, a `Pid` is returned by `spawn/3` and can be stored, passed around, and used to send messages. If a process crashes and is restarted by its supervisor, the new process gets a new `Pid`. External code holding the old `Pid` must be notified of the change and update its reference — or use registered names instead.

This library improves on the Erlang model with `ProcRef<S,M>`, an opaque handle that **survives supervisor restarts**. When a supervisor restarts a crashed process, it creates a new virtual thread and a new `Proc<S,M>`, but it updates the `ProcRef` to point at the new process. External code holding the `ProcRef` continues to send messages to the same reference, and those messages are routed to the new process automatically.

This is a significant ergonomic improvement. In Erlang, processes that want stable references must use the process registry (`register/2` and `whereis/1`) and name-based addressing. In this library, `ProcRef` gives you that stability for free on every process, with the registry (`ProcessRegistry`) available as an additional layer for processes that need to be found by name.

The opacity of `ProcRef` is intentional — callers cannot inspect its internals, cannot obtain the underlying virtual thread reference, and cannot bypass the mailbox to access the process state directly. The only operations on a `ProcRef` are:

- Sending a message (fire-and-forget)
- Asking a question and awaiting a typed reply (`ask`)
- Checking whether the process is alive
- Registering monitors or links

This encapsulation is what makes supervision work: the supervisor can swap out the underlying process without any external callers knowing.

---

## Isolation without shared memory

Java virtual threads do not have per-thread heaps in the same sense as Erlang processes. JVM heap memory is shared by all threads. The isolation in this library is **disciplinary rather than enforced by the runtime**: processes communicate through typed messages rather than shared mutable references.

This is the honest difference between the Java and Erlang implementations. Erlang's per-process heap means that a garbage-collecting process does not stop other processes. Java's shared heap means that a major GC pause affects all virtual threads. For most applications this is acceptable — modern JVMs have very good GC and ZGC pauses are measured in microseconds — but it is a real difference.

The library compensates in two ways:

First, `S` (state) and `M` (message) are intended to be records — immutable, non-sharing data types. When a process sends a message, it sends an immutable object. The sender cannot subsequently modify that object. The isolation is logical even if not enforced by the garbage collector.

Second, the sealed type discipline on `M` means that you cannot accidentally pass a mutable data structure as a message without the type system objecting. If your message types are all records (as they should be), the logical isolation holds.

---

## Process creation is cheap

In Erlang, `spawn/3` is so cheap that production Erlang systems routinely create millions of processes. A web server handling a million simultaneous connections uses a million processes — one per connection. This is not a resource concern; it is the intended design.

With virtual threads, Java achieves similar economics. Creating a `Proc<S,M>` starts a virtual thread, which is a heap allocation of a few kilobytes. The JVM scheduler handles multiplexing onto the carrier thread pool. At rest, a virtual thread blocked on its mailbox consumes only its stack (which is on the heap and compressible) and the mailbox queue.

The practical implication: design your system with one process per logical activity. One process per WebSocket connection. One process per background job. One process per cache client. The per-process overhead does not justify grouping activities together into a shared process.

---

## Scheduling: BEAM vs. JVM

One difference worth understanding is scheduling policy. BEAM uses preemptive scheduling with reduction counting: each process is given a budget of reductions (roughly, function calls), and when the budget is exhausted, the process is preempted and another is scheduled. This means no Erlang process can monopolize the scheduler; all processes make progress.

Java virtual threads use a cooperative scheduling model with some preemption support. A virtual thread yields at blocking points (I/O, `park`, `sleep`). CPU-bound virtual threads that never block can hold their carrier thread indefinitely. For the OTP patterns in this library, this is generally not a problem: processes are expected to handle a message and return, not to spin on a CPU-bound computation. Long CPU-bound work should be submitted to a `ForkJoinPool`, with the result sent back to the process as a message.

This is not a fundamental limitation but a design constraint to be aware of: your process handlers should be non-blocking and return quickly. Heavy computation belongs outside the process loop.

---

## The conceptual mapping summarized

| Erlang concept | Java 26 implementation | Notes |
|---|---|---|
| Process (`spawn/3`) | `Proc<S,M>` on a virtual thread | One-to-one correspondence |
| Process heap | State `S` on thread stack, immutable records | Logical isolation, not runtime-enforced |
| Mailbox | `LinkedTransferQueue<M>` | FIFO, unbounded, lock-free |
| Pid | `ProcRef<S,M>` | Survives supervisor restarts |
| `gen_server` state | Type parameter `S` | Passed as argument to handler, never shared |
| Message | Type parameter `M` | Sealed interface with record subtypes |
| `receive` | Pattern matching switch in handler | Compiler-enforced exhaustiveness |
| Process count | Millions per JVM | Economics comparable to BEAM |

Understanding this mapping is the foundation for understanding everything else in the library. Supervision trees are trees of `ProcRef`s. Links are bilateral watchers between `ProcRef`s. Monitors are unilateral watchers. The registry maps names to `ProcRef`s. Every higher-level abstraction builds on the process/mailbox/reference triad that virtual threads make possible.
