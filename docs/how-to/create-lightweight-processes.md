# How-To: Create Lightweight Processes

This guide shows you how to design and build reusable `Proc<S,M>` abstractions for production use.

## When to Use This Guide

Use this guide when you want to:
- Build reusable process abstractions
- Encapsulate complex state machines
- Create stateful services that handle multiple message types
- Design processes that integrate with other components

## Strategy 1: Sealed Message Hierarchies

Define clear message types using sealed interfaces to ensure exhaustive pattern matching:

```java
import io.github.seanchatmangpt.jotp.*;

public class AuthService {
    // Sealed message hierarchy
    sealed interface AuthMsg permits
        LoginMsg, LogoutMsg, CheckAuthMsg, ListSessionsMsg {}

    record LoginMsg(String user, String password,
        ProcRef<AuthResult, AuthMsg> replyTo) implements AuthMsg {}
    record LogoutMsg(String user) implements AuthMsg {}
    record CheckAuthMsg(String user,
        ProcRef<Boolean, AuthMsg> replyTo) implements AuthMsg {}
    record ListSessionsMsg(ProcRef<List<String>, AuthMsg> replyTo)
        implements AuthMsg {}

    record AuthResult(boolean success, String message) {}

    record AuthState(Map<String, String> sessions) {}

    public static ProcRef<AuthState, AuthMsg> create() {
        return Proc.start(
            state -> msg -> switch(msg) {
                case LoginMsg login -> {
                    // Validate password
                    if (validatePassword(login.user(), login.password())) {
                        state.sessions().put(login.user(),
                            System.currentTimeMillis() + "");
                        login.replyTo().send(
                            new AuthResult(true, "Login successful"));
                    } else {
                        login.replyTo().send(
                            new AuthResult(false, "Invalid credentials"));
                    }
                    yield state;
                }
                case LogoutMsg logout -> {
                    state.sessions().remove(logout.user());
                    yield state;
                }
                case CheckAuthMsg check -> {
                    boolean authed = state.sessions()
                        .containsKey(check.user());
                    check.replyTo().send(authed);
                    yield state;
                }
                case ListSessionsMsg list -> {
                    list.replyTo().send(
                        new ArrayList<>(state.sessions().keySet()));
                    yield state;
                }
            },
            new AuthState(new ConcurrentHashMap<>())
        );
    }

    static boolean validatePassword(String user, String password) {
        // Mock validation
        return password.length() > 0;
    }
}
```

## Strategy 2: Builder Pattern for Complex Initialization

Use builders to configure processes with optional settings:

```java
public class CacheService {
    record CacheMsg(String key, String value) {}
    record GetMsg(String key, ProcRef<String, Object> replyTo) {}

    record CacheState(
        Map<String, String> data,
        Duration ttl,
        long maxSize
    ) {}

    public static class Builder {
        private Duration ttl = Duration.ofMinutes(5);
        private long maxSize = 10000;

        public Builder withTTL(Duration ttl) {
            this.ttl = ttl;
            return this;
        }

        public Builder withMaxSize(long size) {
            this.maxSize = size;
            return this;
        }

        public ProcRef<CacheState, Object> build() {
            var state = new CacheState(
                Collections.synchronizedMap(new LinkedHashMap<>()),
                ttl,
                maxSize
            );

            return Proc.start(
                current -> msg -> switch(msg) {
                    case CacheMsg cache -> {
                        if (current.data().size() < current.maxSize()) {
                            current.data().put(cache.key(),
                                cache.value());
                        }
                        yield current;
                    }
                    case GetMsg get -> {
                        String value = current.data().get(get.key());
                        get.replyTo().send(value);
                        yield current;
                    }
                    default -> current;
                },
                state
            );
        }
    }

    public static Builder newCache() {
        return new Builder();
    }
}
```

## Strategy 3: Process Composition

Combine multiple smaller processes into a larger system:

```java
public class OrderService {
    record OrderMsg(String orderId, String item) {}
    record ProcessMsg(String orderId) {}

    static class ComposedService {
        final ProcRef<?, ?> inventory;
        final ProcRef<?, ?> payment;
        final ProcRef<?, ?> shipping;

        ComposedService(
            ProcRef<?, ?> inventory,
            ProcRef<?, ?> payment,
            ProcRef<?, ?> shipping
        ) {
            this.inventory = inventory;
            this.payment = payment;
            this.shipping = shipping;
        }

        // Create coordinator process that delegates to sub-processes
        ProcRef<ComposedService, String> coordinator() {
            return Proc.start(
                service -> orderId -> {
                    // 1. Check inventory
                    // 2. Process payment
                    // 3. Arrange shipping
                    System.out.println("Processing order: " + orderId);
                    return service;
                },
                this
            );
        }
    }
}
```

## Strategy 4: Error Handling with Result<T,E>

Use `Result<T,E>` for railway-oriented error handling:

```java
public class ValidatingProcess {
    sealed interface Msg permits ValidateMsg, QueryMsg {}
    record ValidateMsg(String input,
        ProcRef<Result<String, String>, Msg> replyTo)
        implements Msg {}
    record QueryMsg(ProcRef<String, Msg> replyTo) implements Msg {}

    public static ProcRef<String, Msg> create() {
        return Proc.start(
            state -> msg -> switch(msg) {
                case ValidateMsg v -> {
                    var result = Result.of(() -> {
                        if (v.input().isEmpty())
                            throw new IllegalArgumentException(
                                "Input cannot be empty");
                        return v.input().toUpperCase();
                    });
                    v.replyTo().send(result);
                    yield state;
                }
                case QueryMsg q -> {
                    q.replyTo().send(state);
                    yield state;
                }
            },
            ""
        );
    }
}
```

## Strategy 5: State Initialization with Factory Methods

Decouple state creation from process creation:

```java
public class DatabaseService {
    record QueryMsg(String sql,
        ProcRef<List<String>, Object> replyTo) {}

    record DbState(
        DataSource dataSource,
        ConnectionPool pool
    ) {}

    static class StateFactory {
        static DbState create(String url, String user, String password)
            throws SQLException {
            var ds = DriverManager.getConnection(url, user, password);
            return new DbState(
                new SimpleDataSource(ds),
                new ConnectionPool(10)
            );
        }
    }

    public static ProcRef<DbState, Object> create(
        String url, String user, String password)
        throws SQLException {
        var state = StateFactory.create(url, user, password);

        return Proc.start(
            dbState -> msg -> switch(msg) {
                case QueryMsg query -> {
                    try (var conn = dbState.pool().acquire();
                         var stmt = conn.createStatement();
                         var rs = stmt.executeQuery(query.sql())) {
                        var results = new ArrayList<String>();
                        while (rs.next()) {
                            results.add(rs.getString(1));
                        }
                        query.replyTo().send(results);
                    } catch (SQLException e) {
                        query.replyTo().send(List.of());
                    }
                    yield dbState;
                }
                default -> dbState;
            },
            state
        );
    }
}
```

## Testing Your Processes

Always test process behavior with proper assertions:

```java
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import java.time.Duration;

public class ProcessTests {
    @Test
    void testAuthServiceLogin() throws Exception {
        var auth = AuthService.create();

        var result = auth.ask(
            replyTo -> new AuthService.LoginMsg("user", "password", replyTo),
            Duration.ofSeconds(1)
        );

        assertThat(result.success()).isTrue();
    }

    @Test
    void testCacheServiceMaxSize() throws Exception {
        var cache = CacheService.newCache()
            .withMaxSize(2)
            .build();

        cache.send(new CacheService.CacheMsg("key1", "value1"));
        cache.send(new CacheService.CacheMsg("key2", "value2"));
        cache.send(new CacheService.CacheMsg("key3", "value3"));

        // Only 2 entries should be stored
        Thread.sleep(50);
    }
}
```

## Best Practices

1. **Use sealed interfaces** for message types — enables exhaustive pattern matching
2. **Design immutable state** — avoid shared mutable state between processes
3. **Separate concerns** — one message type per responsibility
4. **Include request-reply** — use `ProcRef` to return results
5. **Test thoroughly** — verify message handling and state transitions
6. **Document message types** — clarify what each message does
7. **Provide builders** — make configuration flexible and discoverable

## What's Next?

- **[How-To: Send & Receive Messages](send-receive-messages.md)** — Advanced message patterns
- **[How-To: Handle Process Failures](handle-process-failures.md)** — Error recovery
- **[Reference: Proc API](../reference/api-proc.md)** — Complete API documentation

---

**Previous:** [Tutorials](../tutorials/) | **Next:** [Send & Receive Messages](send-receive-messages.md)
