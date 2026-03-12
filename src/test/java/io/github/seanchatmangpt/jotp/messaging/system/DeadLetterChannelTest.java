package io.github.seanchatmangpt.jotp.messaging.system;

import static org.awaitility.Awaitility.await;

import io.github.seanchatmangpt.jotp.*;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import org.assertj.core.api.WithAssertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Verifies Dead Letter Channel pattern for capturing and routing failed messages.
 *
 * <p>Key DLC invariants under test:
 *
 * <ol>
 *   <li>onFailure routes messages to the channel with captured reason
 *   <li>drain() atomically retrieves all accumulated dead letters
 *   <li>drainByReason filters dead letters by reason substring
 *   <li>drainSince filters by timestamp
 *   <li>peek() inspects without removing
 *   <li>withCrashHandler integrates with supervisor crash recovery
 *   <li>size() reflects accumulated count
 * </ol>
 */
@Timeout(10)
class DeadLetterChannelTest implements WithAssertions {

    // Message type for testing
    sealed interface OrderMsg permits OrderMsg.Order, OrderMsg.Invalid {
        record Order(String id, double amount) implements OrderMsg {
            Order {
                if (id == null || id.isBlank()) {
                    throw new IllegalArgumentException("id required");
                }
            }
        }

        record Invalid(String reason) implements OrderMsg {}
    }

    // -------------------------------------------------------
    // 1. Basic onFailure routing
    // -------------------------------------------------------

    @Test
    void onFailure_routesMessageToDLC() {
        var dlc = DeadLetterChannel.<OrderMsg>create();
        var msg = new OrderMsg.Order("ORD-001", 100.0);

        dlc.onFailure(msg, "Insufficient stock");

        var dead = dlc.drain();
        assertThat(dead).hasSize(1);
        assertThat(dead.get(0).message()).isEqualTo(msg);
        assertThat(dead.get(0).reason()).contains("Insufficient stock");
    }

    // -------------------------------------------------------
    // 2. send() alias
    // -------------------------------------------------------

    @Test
    void send_routesMessageToDLC() {
        var dlc = DeadLetterChannel.<OrderMsg>create();
        var msg = new OrderMsg.Order("ORD-002", 50.0);

        dlc.send(msg, "Payment declined");

        assertThat(dlc.size()).isEqualTo(1);
        var dead = dlc.drain();
        assertThat(dead.get(0).reason()).contains("Payment declined");
    }

    // -------------------------------------------------------
    // 3. Atomic drain
    // -------------------------------------------------------

    @Test
    void drain_retrievesAllAndClears() {
        var dlc = DeadLetterChannel.<OrderMsg>create();

        for (int i = 1; i <= 5; i++) {
            var msg = new OrderMsg.Order("ORD-" + i, i * 10.0);
            dlc.onFailure(msg, "Reason " + i);
        }

        assertThat(dlc.size()).isEqualTo(5);

        var first = dlc.drain();
        assertThat(first).hasSize(5);
        assertThat(dlc.size()).isZero();

        // Second drain returns empty
        var second = dlc.drain();
        assertThat(second).isEmpty();
    }

    // -------------------------------------------------------
    // 4. drainByReason filtering
    // -------------------------------------------------------

    @Test
    void drainByReason_filtersMatchingMessages() {
        var dlc = DeadLetterChannel.<OrderMsg>create();

        dlc.onFailure(new OrderMsg.Order("ORD-1", 1), "TimeoutException: Request timed out");
        dlc.onFailure(new OrderMsg.Order("ORD-2", 2), "NullPointerException: Field null");
        dlc.onFailure(new OrderMsg.Order("ORD-3", 3), "TimeoutException: Database timeout");
        dlc.onFailure(new OrderMsg.Order("ORD-4", 4), "ValidationError: Invalid amount");

        var timeouts = dlc.drainByReason("TimeoutException");
        assertThat(timeouts).hasSize(2);
        assertThat(timeouts).allMatch(dl -> dl.reason().contains("TimeoutException"));

        var nullPointers = dlc.drainByReason("NullPointerException");
        assertThat(nullPointers).hasSize(1);

        var validationErrors = dlc.drainByReason("ValidationError");
        assertThat(validationErrors).hasSize(1);
    }

    // -------------------------------------------------------
    // 5. drainSince filtering
    // -------------------------------------------------------

    @Test
    void drainSince_filtersByTimestamp() throws InterruptedException {
        var dlc = DeadLetterChannel.<OrderMsg>create();

        // Add message
        dlc.onFailure(new OrderMsg.Order("ORD-1", 1), "Error 1");

        // Capture checkpoint
        Thread.sleep(100);
        var checkpoint = Instant.now();
        Thread.sleep(100);

        // Add more messages after checkpoint
        dlc.onFailure(new OrderMsg.Order("ORD-2", 2), "Error 2");
        dlc.onFailure(new OrderMsg.Order("ORD-3", 3), "Error 3");

        // Drain only messages after checkpoint
        var since = dlc.drainSince(checkpoint);
        assertThat(since).hasSize(2);
        assertThat(since).allMatch(dl -> !dl.arrivedAt().isBefore(checkpoint));
    }

    // -------------------------------------------------------
    // 6. peek() non-destructive inspection
    // -------------------------------------------------------

    @Test
    void peek_inspectWithoutRemoving() {
        var dlc = DeadLetterChannel.<OrderMsg>create();

        dlc.onFailure(new OrderMsg.Order("ORD-1", 1), "Error 1");
        dlc.onFailure(new OrderMsg.Order("ORD-2", 2), "Error 2");

        // Peek multiple times
        var peeked1 = dlc.peek();
        assertThat(peeked1).isNotNull();
        assertThat(peeked1.message()).isInstanceOf(OrderMsg.Order.class);

        // Size unchanged
        assertThat(dlc.size()).isEqualTo(2);

        var peeked2 = dlc.peek();
        assertThat(peeked2).isEqualTo(peeked1);
        assertThat(dlc.size()).isEqualTo(2);

        // Drain removes
        var all = dlc.drain();
        assertThat(all).hasSize(2);
        assertThat(dlc.peek()).isNull();
    }

    // -------------------------------------------------------
    // 7. withCrashHandler integration
    // -------------------------------------------------------

    @Test
    void withCrashHandler_capturesProcessorExceptions() throws InterruptedException {
        var dlc = DeadLetterChannel.<OrderMsg>create();
        var counter = new AtomicInteger(0);

        var supervisor =
                new Supervisor(
                        "test-sup", Supervisor.Strategy.ONE_FOR_ONE, 5, Duration.ofSeconds(60));

        var handler =
                supervisor.supervise(
                        "test-handler",
                        dlc,
                        dlc.withCrashHandler(
                                msg -> {
                                    switch (msg) {
                                        case OrderMsg.Order order -> {
                                            if (order.amount() < 0) {
                                                throw new IllegalArgumentException(
                                                    "Negative amount: " + order.amount());
                                            }
                                            counter.incrementAndGet();
                                        }
                                        case OrderMsg.Invalid invalid -> {
                                            throw new IllegalStateException(invalid.reason());
                                        }
                                    }
                                }));

        // Valid order — increments counter
        handler.tell(new OrderMsg.Order("ORD-1", 100.0));
        Thread.sleep(200);
        assertThat(counter.get()).isEqualTo(1);

        // Invalid order — caught by crash handler, routed to DLC
        handler.tell(new OrderMsg.Order("ORD-2", -50.0));
        Thread.sleep(200);

        var dead = dlc.drain();
        assertThat(dead).isNotEmpty();
        assertThat(dead.get(0).reason()).contains("Negative amount");

        handler.stop();
    }

    // -------------------------------------------------------
    // 8. Clear operation
    // -------------------------------------------------------

    @Test
    void clear_discardAllMessages() {
        var dlc = DeadLetterChannel.<OrderMsg>create();

        for (int i = 1; i <= 10; i++) {
            dlc.onFailure(new OrderMsg.Order("ORD-" + i, i), "Error " + i);
        }

        assertThat(dlc.size()).isEqualTo(10);

        dlc.clear();

        assertThat(dlc.size()).isZero();
        assertThat(dlc.drain()).isEmpty();
    }

    // -------------------------------------------------------
    // 9. Dead letter envelope metadata
    // -------------------------------------------------------

    @Test
    void deadLetterEnvelope_capturesMetadata() {
        var dlc = DeadLetterChannel.<OrderMsg>create();
        var msg = new OrderMsg.Order("ORD-1", 100.0);
        var before = Instant.now();

        dlc.onFailure(msg, "Test reason");

        var after = Instant.now();
        var dead = dlc.drain().get(0);

        assertThat(dead.message()).isEqualTo(msg);
        assertThat(dead.reason()).isEqualTo("Test reason");
        assertThat(dead.arrivedAt()).isGreaterThanOrEqualTo(before).isLessThanOrEqualTo(after);
    }

    // -------------------------------------------------------
    // 10. Multiple concurrent failures
    // -------------------------------------------------------

    @Test
    void concurrentFailures_allCaptured() throws InterruptedException {
        var dlc = DeadLetterChannel.<OrderMsg>create();

        // Simulate concurrent sends from multiple threads
        var threads =
                new Thread[] {
                    new Thread(
                            () -> {
                                for (int i = 0; i < 10; i++) {
                                    dlc.onFailure(
                                            new OrderMsg.Order("ORD-T1-" + i, i),
                                            "Thread1-Error" + i);
                                }
                            }),
                    new Thread(
                            () -> {
                                for (int i = 0; i < 10; i++) {
                                    dlc.onFailure(
                                            new OrderMsg.Order("ORD-T2-" + i, i),
                                            "Thread2-Error" + i);
                                }
                            })
                };

        for (var t : threads) {
            t.start();
        }

        for (var t : threads) {
            t.join();
        }

        var all = dlc.drain();
        assertThat(all).hasSize(20);
    }

    // -------------------------------------------------------
    // 11. Generic type handling
    // -------------------------------------------------------

    @Test
    void genericTypes_handledCorrectly() {
        var dlcString = DeadLetterChannel.<String>.create();
        var dlcInteger = DeadLetterChannel.<Integer>.create();

        dlcString.onFailure("message1", "Error for string");
        dlcInteger.onFailure(42, "Error for integer");

        var stringDead = dlcString.drain();
        var intDead = dlcInteger.drain();

        assertThat(stringDead).hasSize(1);
        assertThat(stringDead.get(0).message()).isEqualTo("message1");

        assertThat(intDead).hasSize(1);
        assertThat(intDead.get(0).message()).isEqualTo(42);
    }
}
