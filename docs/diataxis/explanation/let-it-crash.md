# The "Let It Crash" Philosophy

"Let it crash" is the most counter-intuitive idea in the Erlang tradition, and the one that produces the most friction for experienced Java developers when they first encounter it. It sounds reckless — an excuse for sloppy error handling. It is, in fact, the opposite: a principled architectural decision that produces software that is *more* reliable than defensively written Java, not less.

Understanding why requires examining what defensive exception handling actually accomplishes, and what it fails to accomplish.

---

## The problem with defensive exception handling

Consider the lifecycle of an exception handler in a typical Java service.

A developer writes a method that calls an external service. The external service can fail in several ways: timeout, connection refused, malformed response, authentication failure. The developer adds a try/catch block that handles each case, logging the error and returning a fallback value or rethrowing a checked exception.

Three months later, a production incident. The external service returns a response that is syntactically valid but semantically incorrect — a field that should always be non-null is null in a specific edge case. The null propagates up through several call frames. A NullPointerException is thrown inside a method that does not expect it. The exception propagates to a try/catch block that catches `Exception` and logs "unexpected error" before returning an empty result. The caller treats the empty result as a cache miss and re-queries the database, causing load to spike. The database starts timing out. The application enters a degraded state that looks like success to the load balancer.

This scenario — which every senior Java developer recognizes from experience — has a specific structure: **in-place recovery from a state that the recovering code did not anticipate**. The catch block that returns an empty result was written defensively, but it was not written with this specific failure mode in mind. Its "recovery" created a secondary failure.

The more catch blocks a codebase has, the more opportunities there are for recovery attempts that themselves fail or produce incorrect behavior. Defensive programming does not eliminate failure; it *transforms* explicit crashes into subtle incorrect behavior that is harder to detect and diagnose.

---

## What "let it crash" actually means

"Let it crash" does not mean "ignore errors." It means: **when an unexpected error occurs, allow the failing process to crash cleanly, and rely on a supervisor to restart it with fresh state.**

The operative word is *unexpected*. There is a clear distinction between:

**Expected failures** — conditions that are a normal part of the business logic. A user submitting invalid input. A payment being declined. A file not found. These are not exceptions; they are outcomes that your code should model explicitly. In this library, they belong in `Result<T,E>` return values, not exception handlers.

**Unexpected failures** — conditions that indicate something is genuinely wrong with the environment or with the code. An assertion that should never be false is false. A data structure that should always be non-null is null. A service that should always be reachable is unreachable in a way that is not covered by the retry logic. These are the failures that "let it crash" addresses.

When an unexpected failure occurs in a process, the correct behavior is:

1. The process crashes — its virtual thread terminates with an exception
2. The exception propagates to the process's supervisor
3. The supervisor logs the crash (with a meaningful stack trace, because the process was a thread with a real call stack)
4. The supervisor restarts the process with its initial state
5. The process begins handling new messages as if it had just started
6. Other processes are unaffected

The result is that the system recovers automatically from a class of failures that defensive code would mask or mishandle. The log contains a clear record of what went wrong. The process state is clean — no half-updated fields, no corrupted caches. And the development cost of the defensive catch block that would have masked the failure was never paid.

---

## The cost-benefit calculation

The argument against "let it crash" from a Java perspective usually sounds like this: "We can't just let things crash in production. We need to handle errors gracefully."

This argument conflates two things: the customer-visible behavior of the system, and the internal behavior of a single process.

When a process in a well-designed supervision tree crashes and is restarted by its supervisor, the customer-visible behavior depends on the system design:

- If the process handles requests synchronously, requests during the restart window may fail with a brief error, or may be queued and retried. This is a design decision made in the supervisor configuration.
- If the process is stateful and maintains a connection (to a database, to a WebSocket client), the connection will be re-established after restart. The client may need to reconnect. This is acceptable behavior that the client should already handle.
- If the process is a background worker, it simply starts from its initial state and picks up where it can.

In none of these cases does the crash make things *worse* than the alternative defensive code would have. The defensive catch block that returns an empty result, or continues processing with corrupted state, produces worse outcomes than a clean crash followed by a clean restart.

---

## How it changes how you write Java

The philosophical shift produces concrete changes in code structure.

### Write the happy path

In traditional Java, a method that calls a flaky external service might look like this conceptually:

```java
// Traditional defensive approach
public Optional<Order> fetchOrder(String id) {
    try {
        Response response = client.get("/orders/" + id);
        if (response.status() == 404) return Optional.empty();
        if (response.status() != 200) {
            log.warn("Unexpected status: {}", response.status());
            return Optional.empty();
        }
        try {
            return Optional.of(parseOrder(response.body()));
        } catch (ParseException e) {
            log.error("Failed to parse order response", e);
            return Optional.empty();
        }
    } catch (IOException e) {
        log.error("Failed to fetch order", e);
        return Optional.empty();
    }
}
```

The process handler equivalent, using `Result<T,E>` for expected failures and allowing unexpected failures to propagate:

```java
// OTP-style: model expected failures, crash on unexpected ones
sealed interface FetchResult permits FetchResult.Found, FetchResult.NotFound {}
record Found(Order order) implements FetchResult {}
record NotFound() implements FetchResult {}

public FetchResult handleFetch(FetchOrderMsg msg) {
    Response response = client.get("/orders/" + msg.id()); // IOException propagates → crash
    return switch (response.status()) {
        case 404 -> new NotFound();
        case 200 -> new Found(parseOrder(response.body())); // ParseException propagates → crash
        default -> throw new IllegalStateException("Unexpected status: " + response.status());
    };
}
```

The second version is shorter, more readable, and has clearer semantics. Network errors and parse errors — which are unexpected in the sense that the code doesn't know how to recover from them — are allowed to propagate. The supervisor handles restart. The 404 case, which is an expected business outcome, is modeled explicitly as a typed result.

### Separate expected from unexpected

The discipline "let it crash" imposes is the discipline of being precise about what your code can handle and what it cannot.

If you know how to recover from a specific failure — a 404 means "this resource doesn't exist, return empty" — model it as a non-exceptional outcome. Use `Result<T,E>`, `Optional`, or a sealed result type. The recovery is explicit and testable.

If you don't know how to recover — an assertion violation, an unexpected null, a response format you've never seen — don't pretend you do. Let the exception propagate, let the process crash, and let the supervisor restart it. The crash produces a log entry with a real stack trace, which is far more useful for debugging than "unexpected error" followed by an empty result.

### State is owned by processes, not shared

The other major change is in how state is managed. Traditional Java services often have shared mutable state — caches, connection pools, configuration objects — accessed by multiple threads under locks.

In the OTP model, state is owned by a process. The state lives on the process's stack (as the `S` type parameter), is never directly accessible to other processes, and is naturally reset when the process restarts. This means:

- No locks on the happy path
- No corrupted state after a crash — restart produces clean initial state
- State transitions are explicit and auditable — every change to state goes through the handler function

A cache, in this model, is a `Proc<CacheState, CacheMsg>` where `CacheState` holds the cached data and `CacheMsg` is a sealed interface of operations (`Lookup`, `Store`, `Invalidate`). Multiple callers send messages to the cache process; the cache process handles them sequentially. No locks. No race conditions. If the cache process crashes — perhaps because a message revealed an invariant violation — it restarts with an empty cache, which is a correct (if cold) state.

### CrashRecovery for controlled retry

Sometimes crashing and restarting is too coarse — you want to retry a specific operation, not restart the entire process. `CrashRecovery` serves this use case.

`CrashRecovery` wraps a supplier in a supervised retry loop using an isolated virtual thread. If the supplier throws, the exception is caught, the virtual thread terminates, and a new virtual thread attempts the operation after a configurable delay. This is more targeted than process restart: the containing process continues to handle other messages while the recovery operation runs, and the result is eventually delivered as a message.

This is the Erlang `proc_lib` + manual retry pattern translated into a clean API.

---

## What you give up

"Let it crash" is not free. It trades one kind of complexity for another.

**You give up in-process recovery.** If a process crashes mid-operation, any partial work it was doing is lost. If the process was updating a database, the update may or may not have been committed — the supervisor restart brings the process back to its initial state, but external effects persist. This means "let it crash" works best with operations that are either atomic (committed or not) or idempotent (safe to retry from the beginning). Long-running operations that have visible external effects at each step require more careful design — either using a state machine to track progress across restarts, or using a saga pattern with compensating transactions.

**You give up detailed in-process error context.** When a defensive catch block catches an exception, it can add context before rethrowing or returning: "Failed to process order #{id} for customer #{customerId}". When you let it crash, the stack trace contains the exception with its natural context, but you don't get to enrich it at each call frame. The mitigation is to use structured logging at the supervisor level, including the process identity and last known state.

**You require supervisor design up front.** A system using "let it crash" needs its supervision tree designed before it can handle failures gracefully. If you dump all your processes under a single root supervisor with ONE_FOR_ALL, a crash in any process restarts everything — probably not what you want. Good supervisor design requires thinking about which process groups are independent and what restart granularity makes sense. This is design work that defensive coding defers (often indefinitely).

---

## The deeper insight: reliability through simplicity

The core insight behind "let it crash" is that reliability comes from simplicity, not from defensive complexity.

A simple process that does one thing well, crashes cleanly when something unexpected happens, and restarts into a known-good state is more reliable than a complex process that tries to handle every possible failure mode and accumulates subtle incorrect state over time.

The traditional intuition — that more error handling makes code more reliable — breaks down when error handlers themselves can fail, when they handle failures in ways that weren't anticipated when they were written, and when they produce incorrect behavior that is harder to detect than an explicit crash.

Thirty years of Erlang production use have validated the opposite intuition: less error handling, enforced isolation, and supervised restart produce systems that are more reliable in practice. The Java 26 virtual thread model makes this architecture available to Java developers for the first time at the right level of abstraction.

---

## The result in practice

Systems written in the OTP style tend to exhibit a characteristic runtime behavior: they crash loudly on edge cases (generating clear log entries with real stack traces), restart automatically, and continue serving traffic. Operators learn to treat a crash log entry not as a crisis but as diagnostic information — something went wrong in process X, it restarted, here is exactly what the state and input were when it failed.

This is fundamentally different from the behavior of defensively-written systems, which tend to degrade silently: no crashes in the log, but incorrect behavior accumulating until a human notices that something seems wrong. By the time the problem is diagnosed, the trail of evidence has been replaced by a long history of "unexpected error, returned empty."

Clean crashes, honest logs, automatic restart: this is what "let it crash" delivers.
