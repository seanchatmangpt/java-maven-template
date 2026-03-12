# Vernon Patterns in JOTP - Quick Reference Card

## 🎯 All 34 Patterns at a Glance

### CHANNELS (3) - How Messages Flow
```
PointToPointChannel   → One-to-one (1:1) async messaging
PublishSubscribeChannel → One-to-many (1:N) broadcasting
DataTypeChannel       → Type-safe sealed interface routing
```

### MESSAGE CONSTRUCTION (4) - How to Build Messages
```
CommandMessage        → Request with embedded replyTo address
DocumentMessage       → Serialize entire domain entities
ClaimCheck           → Split large payload, store externally
EnvelopeWrapper      → Wrap payload with metadata headers
```

### MESSAGE ROUTING (11) - Where Messages Go
```
ContentBasedRouter    → Route by message field values
MessageFilter         → Drop/forward by predicate
DynamicRouter         → Runtime destination lookup
RecipientListRouter   → Fan-out to multiple targets
Splitter             → Break 1 message into many
Aggregator           → Gather N partial → 1 complete message
Resequencer          → Order out-of-order messages
ComposedMessageProcessor → Chain multiple routers/filters
ScatterGather        → Parallel request-reply with fail-fast
RoutingSlip          → Message carries hop list
ProcessManager       → Multi-step workflow orchestration
```

### MESSAGE TRANSFORMATION (3) - Format Conversion
```
MessageTranslator     → A→B transformation function
Normalizer            → Convert to canonical format
FormatIndicator       → Add format metadata headers
```

### INTEGRATION ENDPOINTS (4) - System Integration
```
PollingConsumer       → Periodic message pull from source
CompetingConsumers    → N workers share queue (load balance)
MessageDispatcher     → Bridge sync callers → async receiver
SelectiveConsumer     → Filter consumer (only matching messages)
```

### SYSTEM MANAGEMENT (5) - Reliability & Monitoring
```
IdempotentReceiver    → Dedup by message ID
DeadLetterChannel     → Route poison pills/failures
MessageExpiration     → Discard stale/expired messages
WireTap              → Non-invasive message observation
MessageBridge        → Connect two messaging systems
CorrelationId        → Link related messages across systems
GuaranteedDelivery   → Persist & replay on crash
```

### ADVANCED (2) - Built-in JOTP
```
RequestReply         → Sync call over async channel (Proc.ask)
ReturnAddress        → Implicit reply-to address
```

---

## 💡 One-Minute Patterns

| Pattern | Use Case | Example |
|---------|----------|---------|
| **Point-to-Point** | 1:1 message queue | Order → Warehouse |
| **Pub-Sub** | Fan-out events | UserLogin → [Analytics, Logger, Notifier] |
| **Content Router** | Smart distribution | Route OrderMsg by region field |
| **Splitter** | Decompose | Order with 3 items → 3 ShipmentMsgs |
| **Aggregator** | Collect | 3 Replies → 1 OrderConfirmation |
| **Scatter-Gather** | Parallel query | Ask [DB1, DB2, DB3] → collect answers |
| **Dead Letter** | Error handling | Failed msg → deadLetterQueue |
| **Polling Consumer** | Pull-based | Poll database every 5 seconds |
| **Message Bridge** | System A ↔ B | XML to JSON translator |
| **Wire Tap** | Spy without interfering | Monitor msg flow for metrics |
| **Guaranteed Delivery** | No loss | Persist, retry on crash |
| **Correlation ID** | Trace requests | Track order through saga steps |

---

## 🛠️ Quick API Reference

### Create & Use PointToPointChannel
```java
// Create receiver
var receiver = PointToPointChannel.createReceiver(
    state -> msg -> {
        System.out.println("Got: " + msg);
        return state;
    },
    initialState
);

// Send message (non-blocking)
PointToPointChannel.send(receiver, Message.event("Test", data));

// Send and wait for reply (blocking)
var reply = PointToPointChannel.sendAndWait(receiver, request, 5000);
```

### Create & Use PublishSubscribeChannel
```java
// Create channel
var channel = PublishSubscribeChannel.<Message>create();

// Subscribe
channel.subscribe(msg -> System.out.println("Sub1: " + msg));
channel.subscribe(msg -> System.out.println("Sub2: " + msg));

// Publish (all subscribers notified)
channel.publish(Message.event("OrderCreated", order));

// Sync broadcast (wait for all)
int notifiedCount = channel.publishSync(message);
```

### Create & Use ContentBasedRouter
```java
var router = ContentBasedRouter.create();

// Add routes (predicate → destination)
router.addRoute(msg -> msg.amount > 1000, expensiveHandler);
router.addRoute(msg -> msg.region.equals("EU"), euHandler);
router.addRoute(msg -> true, defaultHandler); // Fallback

// Route messages
router.route(message);
```

### Create & Use Aggregator
```java
// Create aggregator: wait for 3 messages
var agg = Aggregator.create(3, 5000); // 3 parts, 5s timeout

// Add parts as they arrive
var result1 = agg.aggregate(partMsg1);  // null
var result2 = agg.aggregate(partMsg2);  // null
var result3 = agg.aggregate(partMsg3);  // Complete message!
```

### Create & Use CorrelationId
```java
// Generate new correlation ID
var corrId = CorrelationId.generate();

// Attach to messages
var msg1 = CorrelationId.withId(message1, corrId);
var msg2 = CorrelationId.withId(message2, corrId);

// Check if messages are correlated
if (CorrelationId.isCorrelated(msg1, msg2)) {
    // Same conversation
}
```

### Create & Use GuaranteedDelivery
```java
// Create durable wrapper
var durable = GuaranteedDelivery.create(
    receiver,
    new InMemoryMessageStore()  // Or PostgresMessageStore
);

// Send (persisted + retried on crash)
durable.send(importantMessage);

// Check delivery status
if (GuaranteedDelivery.isDelivered(durable, msgId)) {
    // Already processed
}
```

---

## 🔗 JOTP Primitives Cheat Sheet

| Primitive | Use | Example |
|-----------|-----|---------|
| `Proc.spawn()` | Create lightweight process | Receiver for messages |
| `ProcRef` | Process handle (opaque) | Send messages to |
| `EventManager` | Pub-sub event manager | Broadcast to subscribers |
| `StateMachine` | State+Event→Decision | Content Router decisions |
| `ProcessRegistry` | Named process lookup | whereis("myProcess") |
| `ProcessMonitor` | Observe process (no intercept) | Wire Tap monitoring |
| `Supervisor` | Crash recovery + restart | Competing Consumers pool |
| `Parallel` | Structured concurrency | Scatter-Gather fan-out |
| `ProcTimer` | Timed messages | Polling Consumer interval |
| `Proc.ask()` | Request-reply (blocking) | Message Dispatcher |

---

## 📦 Package Structure

```
io.github.seanchatmangpt.jotp.messaging/
├── Message               Sealed base class (EventMsg, CommandMsg, etc)
├── channels/            PointToPoint, PubSub, DataType
├── construction/        CommandMsg, DocumentMsg, ClaimCheck, Envelope
├── routing/            ContentRouter, Filter, Splitter, Aggregator, etc.
├── transformation/      Translator, Normalizer, FormatIndicator
├── endpoints/          PollingConsumer, Competing, Dispatcher, Selective
└── system/             Idempotent, DeadLetter, Expiration, WireTap, etc.
```

---

## 📖 Documentation

| Resource | Purpose |
|----------|---------|
| `VERNON_PATTERNS.md` | Complete pattern mappings + code |
| `VERNON_PATTERNS_STATUS.md` | Implementation status |
| `IMPLEMENTATION_SUMMARY.md` | Architecture + testing details |
| `QUICK_REFERENCE.md` | This card |

---

## 🧪 Testing Patterns

```bash
# All channel tests
mvnd test -Dtest=*ChannelTest

# All routing tests
mvnd test -Dtest=*RouterTest

# Specific pattern
mvnd test -Dtest=AggregatorTest

# Run examples
mvnd exec:java -Dexec.mainClass="...PointToPointChannelExample"
```

---

## 🚀 Common Scenarios

### Scenario: Process Orders in Parallel
```java
// 1. Receive order (Point-to-Point)
var receiver = PointToPointChannel.createReceiver(...);

// 2. Route by region (Content Router)
router.addRoute(msg -> msg.region == "US", usProcessor);
router.addRoute(msg -> msg.region == "EU", euProcessor);

// 3. Fan-out to logistics (Recipient List)
handlers = [warehouseHandler, shippingHandler, analyticsHandler];
RecipientListRouter.broadcastMessage(handlers, order);

// 4. Guarantee delivery (Guaranteed Delivery)
durable = GuaranteedDelivery.create(receiver, store);
durable.send(order);
```

### Scenario: Aggregate Results from Multiple Sources
```java
// Create aggregator for 3 results
var agg = Aggregator.create(3, 5000);

// Query all sources (Scatter-Gather)
Parallel.all(
    () -> dbSource.ask(query),
    () -> cacheSource.ask(query),
    () -> serviceSource.ask(query)
).thenApply(replies -> {
    agg.aggregate(reply1);
    agg.aggregate(reply2);
    return agg.aggregate(reply3);  // Complete
});
```

### Scenario: Monitor Message Flow
```java
// Non-invasively observe
var tap = WireTap.tap(
    targetProcess,
    msg -> System.out.println("Saw: " + msg)
);

// Get captured messages
List<Message> observed = WireTap.getCaptured(tap);
```

---

## ⚙️ Configuration Tips

**Polling Consumer Interval**:
```java
var poller = PollingConsumer.create(source, 1000); // 1s interval
```

**Message Expiration**:
```java
var expired = MessageExpiration.withExpiration(msg, 60000); // 60s TTL
```

**Correlation Tracking**:
```java
var context = CorrelationId.startTracking(corrId);
// ... do work, record messages
CorrelationId.completeTracking(corrId);
```

**Guaranteed Delivery Storage**:
```java
// In-memory (demo)
new GuaranteedDelivery.InMemoryMessageStore()

// Persistent (production)
new PostgresMessageStore(datasource)
```

---

## 🎓 Learning Path

1. **Start**: PointToPointChannel + PublishSubscribeChannel
2. **Progress**: ContentBasedRouter + MessageFilter
3. **Intermediate**: Aggregator + Splitter + Scatter-Gather
4. **Advanced**: ProcessManager + GuaranteedDelivery + CorrelationId
5. **Master**: Combine multiple patterns (see VERNON_PATTERNS.md examples)

---

## 💪 Best Practices

✅ **DO**:
- Use sealed messages for type safety
- Compose patterns via standard interfaces
- Test with multiple concurrent clients
- Monitor with Wire Tap, not instrumentation
- Use GuaranteedDelivery for critical paths

❌ **DON'T**:
- Cast Message types (use pattern matching)
- Block on ProcRef sends (always non-blocking)
- Create unnecessary Supervisor restarts
- Ignore message timestamps
- Mix JOTP processes with thread pools

---

## 📞 Need Help?

- **API**: Check Javadoc in each class
- **Examples**: See *Example.java files
- **Tests**: Look at *Test.java for usage patterns
- **Master Guide**: Read docs/VERNON_PATTERNS.md
- **JOTP Docs**: See phd-thesis-otp-java26.md

---

**Version**: 1.0
**Date**: 2026-03-12
**Java**: 26 (with preview features)
**Framework**: JOTP + Messaging

Print this card, keep it handy! 📋
