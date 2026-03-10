# 22. Process Manager

> *"A Process Manager orchestrates a multi-step business process, routing messages between services and tracking the overall state of the workflow."*

## Intent

A Process Manager (also known as a Saga Orchestrator) coordinates a long-running business transaction that spans multiple services. It receives events from services, determines the next step, sends commands to the appropriate service, and maintains the current state of the workflow. If a step fails, it can issue compensating commands.

## OTP Analogy

OTP's `gen_statem` is the native Process Manager — a process with an explicit state machine that drives a workflow. Supervisors wrap it for fault tolerance:

```erlang
%% Order saga as a gen_statem
handle_event(cast, payment_succeeded, payment, Data) ->
    gen_server:cast(ShippingService, {ship_order, Data#data.order_id}),
    {next_state, shipping, Data};

handle_event(cast, payment_failed, payment, Data) ->
    gen_server:cast(OrderService, {cancel_order, Data#data.order_id}),
    {next_state, cancelled, Data}.
```

JOTP maps this directly to `StateMachine<S,E,D>` under a `Supervisor`.

## JOTP Implementation

Combine `StateMachine<S,E,D>` (state logic) with `Supervisor` (fault tolerance) and `ProcessRegistry` (named lookup):

```
Supervisor (ONE_FOR_ONE)
   └── OrderSaga StateMachine<OrderState, OrderEvent, OrderData>
           │
    PENDING ──[PaymentRequested]──► PAYMENT
    PAYMENT ──[PaymentSucceeded]──► SHIPPING
    PAYMENT ──[PaymentFailed]────► CANCELLED (compensate)
    SHIPPING ──[Shipped]─────────► DELIVERED
    SHIPPING ──[ShipFailed]──────► PAYMENT_REFUND (compensate)
```

Each state transition triggers a command to a downstream service. The `StateMachine` is the single source of truth for saga state; all steps flow through it.

Key design points:
- `StateMachine<S,E,D>` separates: `S` = current state (enum), `E` = event (sealed interface), `D` = immutable data (record).
- The `Supervisor` restarts the saga process if it crashes; the state machine re-initialises from a persisted checkpoint.
- `ProcessRegistry` allows external services to look up the saga by `orderId`.
- Use `ProcTimer` to implement saga timeouts (e.g., payment must succeed within 5 minutes).

## API Reference

| Class / Method | Description |
|----------------|-------------|
| `StateMachine<S,E,D>` | State machine: state + event + data |
| `Supervisor` (ONE_FOR_ONE) | Restart the saga process on failure |
| `ProcessRegistry.register(name, proc)` | Name the saga for external lookup |
| `ProcTimer.sendAfter(delay, proc, event)` | Timeout event delivery |
| `stateMachine.send(event)` | Drive the saga with an event |

## Code Example

```java
import org.acme.StateMachine;
import org.acme.Supervisor;
import org.acme.ProcessRegistry;

// --- Saga states ---
enum OrderState { PENDING, PAYMENT, SHIPPING, DELIVERED, CANCELLED }

// --- Saga events (sealed) ---
sealed interface OrderEvent
    permits PaymentRequested, PaymentSucceeded, PaymentFailed,
            ShipmentDispatched, ShipmentFailed, OrderCancelled {}

record PaymentRequested(double amount)  implements OrderEvent {}
record PaymentSucceeded(String txnId)   implements OrderEvent {}
record PaymentFailed(String reason)     implements OrderEvent {}
record ShipmentDispatched(String track) implements OrderEvent {}
record ShipmentFailed(String reason)    implements OrderEvent {}
record OrderCancelled(String reason)    implements OrderEvent {}

// --- Saga data ---
record OrderData(String orderId, String customerId, double amount,
                 String txnId, String trackingId) {
    OrderData withTxn(String t)      { return new OrderData(orderId, customerId, amount, t, trackingId); }
    OrderData withTracking(String t) { return new OrderData(orderId, customerId, amount, txnId, t); }
}

// --- Saga definition ---
public class OrderSaga {

    static StateMachine.Transition<OrderState, OrderEvent, OrderData>
    transition(OrderState state, OrderEvent event, OrderData data) {
        return switch (state) {
            case PENDING -> switch (event) {
                case PaymentRequested p -> {
                    requestPayment(data.orderId(), p.amount());
                    yield StateMachine.next(OrderState.PAYMENT, data);
                }
                default -> StateMachine.ignore();
            };
            case PAYMENT -> switch (event) {
                case PaymentSucceeded s -> {
                    dispatchShipment(data.orderId());
                    yield StateMachine.next(OrderState.SHIPPING, data.withTxn(s.txnId()));
                }
                case PaymentFailed f -> {
                    cancelOrder(data.orderId(), f.reason());
                    yield StateMachine.next(OrderState.CANCELLED, data);
                }
                default -> StateMachine.ignore();
            };
            case SHIPPING -> switch (event) {
                case ShipmentDispatched d2 ->
                    StateMachine.next(OrderState.DELIVERED, data.withTracking(d2.track()));
                case ShipmentFailed f -> {
                    refundPayment(data.txnId());
                    yield StateMachine.next(OrderState.CANCELLED, data);
                }
                default -> StateMachine.ignore();
            };
            case DELIVERED, CANCELLED -> StateMachine.ignore(); // terminal states
        };
    }

    static void requestPayment(String orderId, double amt) {
        System.out.printf("[SAGA] Requesting payment %.2f for %s%n", amt, orderId); }
    static void dispatchShipment(String orderId) {
        System.out.printf("[SAGA] Dispatching shipment for %s%n", orderId); }
    static void cancelOrder(String orderId, String reason) {
        System.out.printf("[SAGA] Cancelling %s: %s%n", orderId, reason); }
    static void refundPayment(String txnId) {
        System.out.printf("[SAGA] Refunding txn %s%n", txnId); }

    public static void main(String[] args) throws Exception {
        var data = new OrderData("ORD-001", "CUST-007", 149.99, null, null);
        var saga = StateMachine.of(OrderState.PENDING, data, OrderSaga::transition);

        // Register under the orderId for external event routing
        ProcessRegistry.register("saga:ORD-001", saga.proc());

        // Drive the saga with events
        saga.send(new PaymentRequested(149.99));
        saga.send(new PaymentSucceeded("TXN-ABC"));
        saga.send(new ShipmentDispatched("TRACK-XYZ"));

        System.out.println("Final state: " + saga.currentState()); // DELIVERED
    }
}
```

## Test Pattern

```java
import org.junit.jupiter.api.Test;
import org.assertj.core.api.WithAssertions;

class ProcessManagerTest implements WithAssertions {

    @Test
    void happyPathReachesDelivered() throws Exception {
        var data = new OrderData("ORD-T1", "C1", 50.0, null, null);
        var saga = StateMachine.of(OrderState.PENDING, data, OrderSaga::transition);

        saga.send(new PaymentRequested(50.0));
        saga.send(new PaymentSucceeded("TXN-1"));
        saga.send(new ShipmentDispatched("TRACK-1"));

        Thread.sleep(50); // let state machine process
        assertThat(saga.currentState()).isEqualTo(OrderState.DELIVERED);
    }

    @Test
    void paymentFailureReachesCancelled() throws Exception {
        var data = new OrderData("ORD-T2", "C2", 50.0, null, null);
        var saga = StateMachine.of(OrderState.PENDING, data, OrderSaga::transition);

        saga.send(new PaymentRequested(50.0));
        saga.send(new PaymentFailed("card declined"));

        Thread.sleep(50);
        assertThat(saga.currentState()).isEqualTo(OrderState.CANCELLED);
    }
}
```

## Caveats & Trade-offs

**Use when:**
- A business transaction spans multiple services with compensating actions on failure.
- Workflow state needs to be durable and inspectable at any point.
- You need timeout-driven transitions (e.g., payment window expires).

**Avoid when:**
- The workflow has only one step — a simple `proc.tell(cmd)` suffices.
- You need distributed saga persistence across JVM restarts — augment with an event store or database checkpoint; `StateMachine` is in-memory only.
- Services are choreography-based (each service reacts to events independently) — orchestration and choreography are architectural alternatives, not complements.
