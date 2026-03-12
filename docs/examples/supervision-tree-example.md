# Examples: Supervision Tree Example

Complete example of a multi-level supervision hierarchy.

## System Architecture

```
RootSupervisor (ONE_FOR_ONE)
├── Database Service
│   └── Proc (connection manager)
├── Cache Service
│   └── Proc (in-memory cache)
└── API Service
    ├── RequestHandler (supervised)
    └── ResponseWriter (supervised)
```

## Implementation

```java
import io.github.seanchatmangpt.jotp.*;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

public class SupervisionTreeExample {
    // ==================== Service Definitions ====================

    // Database Service
    sealed interface DbMsg permits QueryMsg {}
    record QueryMsg(String sql, ProcRef<String, DbMsg> replyTo) implements DbMsg {}

    static class DatabaseService {
        static ProcRef<Map<String, String>, DbMsg> create() {
            return Proc.start(
                db -> msg -> switch(msg) {
                    case QueryMsg q -> {
                        String result = db.getOrDefault(q.sql(), "NOT_FOUND");
                        q.replyTo().send(result);
                        yield db;
                    }
                },
                new ConcurrentHashMap<>()
            );
        }
    }

    // Cache Service
    sealed interface CacheMsg permits GetMsg, SetMsg {}
    record GetMsg(String key, ProcRef<String, CacheMsg> replyTo) implements CacheMsg {}
    record SetMsg(String key, String value) implements CacheMsg {}

    static class CacheService {
        static ProcRef<Map<String, String>, CacheMsg> create() {
            return Proc.start(
                cache -> msg -> switch(msg) {
                    case GetMsg g -> {
                        g.replyTo().send(cache.get(g.key()));
                        yield cache;
                    }
                    case SetMsg s -> {
                        cache.put(s.key(), s.value());
                        yield cache;
                    }
                },
                new ConcurrentHashMap<>()
            );
        }
    }

    // Request Handler
    sealed interface HandlerMsg permits ProcessMsg {}
    record ProcessMsg(String request,
        ProcRef<String, HandlerMsg> replyTo) implements HandlerMsg {}

    static class RequestHandler {
        static ProcRef<Void, HandlerMsg> create() {
            return Proc.start(
                _ -> msg -> switch(msg) {
                    case ProcessMsg p -> {
                        String response = "Processed: " + p.request();
                        p.replyTo().send(response);
                        return null;
                    }
                },
                null
            );
        }
    }

    // ==================== Root Supervisor ====================

    static ProcRef<Void, Object> createRootSupervisor() {
        return Supervisor.oneForOne()
            .add("database", DatabaseService::create)
            .add("cache", CacheService::create)
            .add("handler", RequestHandler::create)
            .build();
    }

    // ==================== Main ====================

    public static void main(String[] args) throws Exception {
        // Start the supervision tree
        var root = createRootSupervisor();

        // Look up services
        var db = root.whereis("database");
        var cache = root.whereis("cache");
        var handler = root.whereis("handler");

        System.out.println("Supervision tree started");

        // Use the services
        System.out.println("1. Using cache service...");
        cache.send(new SetMsg("key1", "value1"));
        String cached = cache.ask(
            replyTo -> new GetMsg("key1", replyTo),
            Duration.ofSeconds(1)
        );
        System.out.println("Cache result: " + cached);

        System.out.println("\n2. Using request handler...");
        String response = handler.ask(
            replyTo -> new ProcessMsg("hello", replyTo),
            Duration.ofSeconds(1)
        );
        System.out.println("Handler response: " + response);

        System.out.println("\n3. All services running under supervision");
        System.out.println("If any service crashes, supervisor will restart it");
    }
}
```

## With Tests

```java
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import java.time.Duration;

public class SupervisionTreeTest {
    @Test
    void testSupervisorStartup() throws Exception {
        var root = SupervisionTreeExample.createRootSupervisor();

        assertThat(root.whereis("database")).isNotNull();
        assertThat(root.whereis("cache")).isNotNull();
        assertThat(root.whereis("handler")).isNotNull();
    }

    @Test
    void testCacheServiceThroughSupervisor() throws Exception {
        var root = SupervisionTreeExample.createRootSupervisor();
        var cache = root.whereis("cache");

        cache.send(new SupervisionTreeExample.SetMsg("test_key", "test_value"));

        String result = cache.ask(
            replyTo -> new SupervisionTreeExample.GetMsg("test_key", replyTo),
            Duration.ofSeconds(1)
        );

        assertThat(result).isEqualTo("test_value");
    }

    @Test
    void testHandlerServiceThroughSupervisor() throws Exception {
        var root = SupervisionTreeExample.createRootSupervisor();
        var handler = root.whereis("handler");

        String response = handler.ask(
            replyTo -> new SupervisionTreeExample.ProcessMsg("test", replyTo),
            Duration.ofSeconds(1)
        );

        assertThat(response).contains("Processed:");
    }
}
```

## Key Points

1. **Hierarchical Structure** — Services can be nested under supervisors
2. **One-For-One Strategy** — Each service restarts independently
3. **Service Discovery** — Use `whereis()` to find services by name
4. **Isolation** — Each service maintains its own state
5. **Resilience** — Supervisor automatically restarts crashed services

## What's Next?

- **[Message Passing Example](message-passing-example.md)** — Inter-process patterns
- **[How-To: Build Supervision Trees](../how-to/build-supervision-trees.md)** — Advanced supervision
- **[Tutorial: Supervision Basics](../tutorials/04-supervision-basics.md)** — Detailed explanation

---

**See Also:** [Reference: Supervisor API](../reference/api-supervisor.md) | [Reference: Glossary](../reference/glossary.md)
