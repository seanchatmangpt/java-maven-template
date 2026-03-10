# 10. Request-Reply

> *"When a requestor needs a response, it sends a Request and waits for a Reply — two one-way messages forming a logical round trip."*

## Intent

Request-Reply enables synchronous-style interaction over an asynchronous channel. The requestor sends a message and waits for a correlated response, without polling. In JOTP, this is a first-class primitive: `proc.ask(msg)` returns a `CompletableFuture<S>` that resolves when the process has processed the message and the updated state is available.

## OTP Analogy

`gen_server:call/3` is Erlang's Request-Reply primitive. It sends a message and blocks the caller (with a timeout) until the server replies:

```erlang
%% gen_server:call blocks the caller — default 5000 ms timeout
{ok, Balance} = gen_server:call(AccountServer, {get_balance, "ACC-001"}).

%% With explicit timeout
{ok, Balance} = gen_server:call(AccountServer,
                                {get_balance, "ACC-001"},
                                2000).   % 2 seconds

%% Server replies via gen_server:reply/2 or return value
handle_call({get_balance, AccId}, _From, State) ->
    {reply, {ok, maps:get(AccId, State)}, State}.
```

`proc.ask(msg)` is the JOTP equivalent — it sends `msg` to the process mailbox and returns a `CompletableFuture<S>` that resolves to the new process state after the message is handled.

> **CRITICAL: always use a timeout with `ask()`.**
> An unresolved future will block the caller thread indefinitely if the process crashes or the message is never processed. Always use `future.get(N, TimeUnit.SECONDS)`.

## JOTP Implementation

```
Requestor                     Proc<S, M>
    │── proc.ask(msg) ──────►  mailbox
    │                          handler(state, msg) → newState
    │◄── CompletableFuture ───  future.complete(newState)
    │    .get(2, SECONDS)
```

Key design points:
- `ask()` delivers the message to the mailbox and returns immediately with a `CompletableFuture<S>`.
- The future resolves to the **new state** after the handler returns, not to an explicit reply value.
- For richer replies (not the full state), embed a `CompletableFuture<R>` in the request record (see Pattern 11: Return Address).
- Timeout is mandatory — wrap the `get()` call or use `orTimeout()` on the future.
- Multiple concurrent `ask()` calls are safe: each gets its own `CompletableFuture`.

## API Reference

| Method | Description |
|--------|-------------|
| `proc.tell(msg)` | Fire-and-forget (no reply) |
| `proc.ask(msg)` | Request-Reply; returns `CompletableFuture<S>` |
| `future.get(2, TimeUnit.SECONDS)` | Block with mandatory timeout |
| `future.orTimeout(2, TimeUnit.SECONDS)` | Attach timeout without blocking |
| `future.thenApply(fn)` | Non-blocking transformation of reply |

## Code Example

```java
import org.acme.Proc;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

// --- State and messages ---
record AccountState(String accountId, double balance) {}

sealed interface AccountMsg permits Deposit, Withdraw, GetBalance {}
record Deposit(double amount)   implements AccountMsg {}
record Withdraw(double amount)  implements AccountMsg {}
record GetBalance()             implements AccountMsg {}

// --- Service ---
public class AccountService {

    static AccountState handle(AccountState state, AccountMsg msg) {
        return switch (msg) {
            case Deposit d  -> new AccountState(state.accountId(),
                                                state.balance() + d.amount());
            case Withdraw w -> {
                if (w.amount() > state.balance())
                    throw new IllegalStateException("Insufficient funds");
                yield new AccountState(state.accountId(),
                                       state.balance() - w.amount());
            }
            case GetBalance ignored -> state; // read-only, state unchanged
        };
    }

    public static void main(String[] args) throws Exception {
        var proc = Proc.of(
            new AccountState("ACC-001", 1000.0),
            AccountService::handle
        );

        // Fire-and-forget mutations
        proc.tell(new Deposit(500.0));
        proc.tell(new Withdraw(200.0));

        // Request-reply read — ALWAYS use timeout
        var future  = proc.ask(new GetBalance());
        var state   = future.get(2, TimeUnit.SECONDS);   // mandatory timeout
        System.out.printf("Balance: %.2f%n", state.balance());  // 1300.0

        // Non-blocking style using orTimeout + thenApply
        proc.ask(new GetBalance())
            .orTimeout(2, TimeUnit.SECONDS)
            .thenApply(AccountState::balance)
            .thenAccept(b -> System.out.printf("Async balance: %.2f%n", b));
    }
}
```

## Test Pattern

```java
import org.junit.jupiter.api.Test;
import org.assertj.core.api.WithAssertions;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

class RequestReplyTest implements WithAssertions {

    Proc<AccountState, AccountMsg> freshAccount() {
        return Proc.of(new AccountState("ACC-T1", 500.0), AccountService::handle);
    }

    @Test
    void askReturnsUpdatedStateAfterMutation() throws Exception {
        var proc  = freshAccount();
        proc.tell(new Deposit(100.0));

        var state = proc.ask(new GetBalance()).get(2, TimeUnit.SECONDS);
        assertThat(state.balance()).isEqualTo(600.0);
    }

    @Test
    void multipleConcurrentAsksAreIndependent() throws Exception {
        var proc = freshAccount();

        var f1 = proc.ask(new GetBalance());
        var f2 = proc.ask(new GetBalance());

        // Both futures resolve independently
        assertThat(f1.get(2, TimeUnit.SECONDS).balance()).isEqualTo(500.0);
        assertThat(f2.get(2, TimeUnit.SECONDS).balance()).isEqualTo(500.0);
    }

    @Test
    void timeoutIsEnforcedWhenProcessIsUnresponsive() {
        // Simulate a stalled process by not creating one —
        // demonstrate the timeout API contract:
        var future = new java.util.concurrent.CompletableFuture<AccountState>();
        future.orTimeout(100, TimeUnit.MILLISECONDS);

        assertThatThrownBy(() -> future.get(200, TimeUnit.MILLISECONDS))
            .isInstanceOf(TimeoutException.class);
    }
}
```

## Caveats & Trade-offs

**Use when:**
- The caller requires a response before proceeding.
- You are querying process state without exposing it outside the Proc boundary.
- Building a synchronous-over-async bridge (e.g., REST handler → `proc.ask()`).

**Avoid when:**
- No reply is needed — use `tell()` to avoid unnecessary `CompletableFuture` allocation.
- The reply payload is richer than the full state — embed a `CompletableFuture<R>` return address in the request record (Pattern 11) instead of exposing the entire state.
- You omit the timeout — a stalled process will leak the caller thread.
