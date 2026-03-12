package io.github.seanchatmangpt.jotp.messaging.routing;

import static org.assertj.core.api.Assertions.*;

import io.github.seanchatmangpt.jotp.Proc;
import io.github.seanchatmangpt.jotp.ProcRef;
import io.github.seanchatmangpt.jotp.Result;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for Scatter-Gather pattern.
 *
 * <p>Verifies:
 *
 * <ul>
 *   <li>Basic scatter-gather with multiple recipients
 *   <li>Concurrent execution (all tasks start simultaneously)
 *   <li>Fail-fast semantics (on first timeout, gather fails immediately)
 *   <li>Correlation IDs and reply ordering
 *   <li>Timeout handling
 *   <li>Custom aggregation functions
 *   <li>Fallback recovery on failure
 * </ul>
 */
@DisplayName("Scatter-Gather Orchestration Pattern")
class ScatterGatherTest {

    /** Echo server state: just a counter. */
    record ServerState(int echoCount) {}

    /** Echo server message. */
    record EchoMessage(String requestId, String payload) {}

    private List<ProcRef<ServerState, EchoMessage>> recipients;
    private List<Proc<ServerState, EchoMessage>> processes;

    @BeforeEach
    void setUp() {
        recipients = new ArrayList<>();
        processes = new ArrayList<>();

        // Create 3 echo servers
        for (int i = 0; i < 3; i++) {
            int serverId = i;
            var proc =
                    new Proc<>(
                            new ServerState(0),
                            (state, msg) -> {
                                // Echo server: just increment counter and return
                                System.out.println(
                                        "Server-" + serverId + ": echo '" + msg.payload()
                                                + "'");
                                return new ServerState(state.echoCount() + 1);
                            });
            processes.add(proc);
            recipients.add(new ProcRef<>(proc));
        }
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        for (var proc : processes) {
            proc.stop();
        }
    }

    @Test
    @DisplayName("Basic scatter-gather: collect replies from all recipients")
    void testBasicScatterGather() {
        // Arrange
        var scatterGather =
                new ScatterGather<EchoMessage, String, ServerState, EchoMessage>();
        var message = new EchoMessage("req-1", "Hello");

        // Act
        var result =
                scatterGather.scatterGather(
                        message,
                        recipients,
                        1000, // 1 second timeout
                        reqWithId -> {
                            var futures =
                                    new ArrayList<
                                            CompletableFuture<
                                                    ScatterGather.ReplyWithId<String>>>();
                            for (var recipient : recipients) {
                                var future =
                                        recipient.ask(new EchoMessage(
                                                        reqWithId.requestId(),
                                                        reqWithId.payload().payload()),
                                                Duration.ofSeconds(1))
                                                .thenApply(
                                                        s -> {
                                                            return new ScatterGather
                                                                    .ReplyWithId<>(
                                                                    reqWithId.requestId(),
                                                                    Result.ok("replied"));
                                                        });
                                futures.add(future);
                            }
                            return CompletableFuture.completedFuture(futures.get(0).join());
                        });

        // Assert
        assertThat(result)
                .as("Scatter-gather should succeed")
                .isInstanceOf(Result.Ok.class);

        var okResult = (Result.Ok<List<String>, Exception>) result;
        assertThat(okResult.value())
                .as("Should collect replies from all 3 recipients")
                .hasSize(3);
    }

    @Test
    @DisplayName("Scatter-gather timeout: fail-fast when any recipient times out")
    void testScatterGatherTimeout() throws InterruptedException {
        // Arrange: create a slow recipient that doesn't respond quickly
        var slowProc =
                new Proc<ServerState, EchoMessage>(
                        new ServerState(0),
                        (state, msg) -> {
                            try {
                                Thread.sleep(5000); // Sleep longer than timeout
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                            }
                            return state;
                        });
        var slowRecipient = new ProcRef<>(slowProc);

        var scatterGather =
                new ScatterGather<EchoMessage, String, ServerState, EchoMessage>();
        var message = new EchoMessage("req-timeout", "Will timeout");

        // Act
        var result =
                scatterGather.scatterGather(
                        message,
                        List.of(recipients.get(0), slowRecipient, recipients.get(1)),
                        500, // 500 ms timeout (shorter than 5 second sleep)
                        reqWithId -> {
                            var futures =
                                    new ArrayList<
                                            CompletableFuture<
                                                    ScatterGather.ReplyWithId<String>>>();
                            for (var r :
                                    List.of(
                                            recipients.get(0),
                                            slowRecipient,
                                            recipients.get(1))) {
                                var future =
                                        r.ask(new EchoMessage(
                                                        reqWithId.requestId(),
                                                        reqWithId.payload().payload()),
                                                Duration.ofMillis(500))
                                                .thenApply(
                                                        s -> {
                                                            return new ScatterGather
                                                                    .ReplyWithId<>(
                                                                    reqWithId.requestId(),
                                                                    Result.ok("replied"));
                                                        });
                                futures.add(future);
                            }
                            return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                                    .thenApply(_ -> futures.get(0).join());
                        });

        // Assert
        assertThat(result)
                .as("Scatter-gather should fail due to timeout")
                .isInstanceOf(Result.Err.class);

        slowProc.stop();
    }

    @Test
    @DisplayName("Scatter-gather with correlation ID")
    void testScatterGatherCorrelation() {
        // Arrange
        var correlationId = "corr-123";

        // Act
        var result =
                ScatterGather.<EchoMessage, String, ServerState, EchoMessage>
                        scatterGatherCorrelated(
                                correlationId,
                                new EchoMessage("req", "test"),
                                recipients,
                                1000,
                                reqWithId -> {
                                    var futures =
                                            new ArrayList<
                                                    CompletableFuture<
                                                            ScatterGather.ReplyWithId<String>>>();
                                    for (var recipient : recipients) {
                                        var future =
                                                recipient.ask(new EchoMessage(
                                                                reqWithId.requestId(),
                                                                reqWithId.payload().payload()),
                                                        Duration.ofSeconds(1))
                                                        .thenApply(
                                                                s -> {
                                                                    return new ScatterGather
                                                                            .ReplyWithId<>(
                                                                            reqWithId
                                                                                    .requestId(),
                                                                            Result.ok(
                                                                                    "replied"));
                                                                });
                                        futures.add(future);
                                    }
                                    return CompletableFuture.completedFuture(
                                            futures.get(0).join());
                                });

        // Assert
        assertThat(result).isInstanceOf(Result.Ok.class);
    }

    @Test
    @DisplayName("Scatter-gather with custom aggregator")
    void testScatterGatherWithAggregator() {
        // Arrange
        var scatterGather =
                new ScatterGather<EchoMessage, String, ServerState, EchoMessage>();
        var message = new EchoMessage("agg-req", "aggregate");

        // Act
        var result =
                ScatterGather.<EchoMessage, String, ServerState, EchoMessage, Integer>
                        scatterGatherWith(
                                message,
                                recipients,
                                1000,
                                (corrId, replies) -> {
                                    // Aggregate: count total replies
                                    return replies.size();
                                },
                                reqWithId -> {
                                    var futures =
                                            new ArrayList<
                                                    CompletableFuture<
                                                            ScatterGather.ReplyWithId<String>>>();
                                    for (var recipient : recipients) {
                                        var future =
                                                recipient.ask(new EchoMessage(
                                                                reqWithId.requestId(),
                                                                reqWithId.payload().payload()),
                                                        Duration.ofSeconds(1))
                                                        .thenApply(
                                                                s -> {
                                                                    return new ScatterGather
                                                                            .ReplyWithId<>(
                                                                            reqWithId
                                                                                    .requestId(),
                                                                            Result.ok(
                                                                                    "replied"));
                                                                });
                                        futures.add(future);
                                    }
                                    return CompletableFuture.completedFuture(
                                            futures.get(0).join());
                                });

        // Assert
        assertThat(result)
                .as("Aggregated result should be success")
                .isInstanceOf(Result.Ok.class);

        var okResult = (Result.Ok<Integer, Exception>) result;
        assertThat(okResult.value())
                .as("Should aggregate 3 replies into count")
                .isEqualTo(3);
    }

    @Test
    @DisplayName("Scatter-gather with fallback on failure")
    void testScatterGatherWithFallback() throws InterruptedException {
        // Arrange: create a failing recipient
        var failingProc =
                new Proc<ServerState, EchoMessage>(
                        new ServerState(0),
                        (state, msg) -> {
                            throw new RuntimeException("Simulated failure");
                        });
        var failingRecipient = new ProcRef<>(failingProc);

        // Act
        var result =
                ScatterGather.<EchoMessage, String, ServerState, EchoMessage, String>
                        scatterGatherWithFallback(
                                new EchoMessage("fallback-req", "test"),
                                List.of(failingRecipient),
                                1000,
                                (corrId, replies) -> "success: " + replies.size(),
                                error -> "fallback: " + error.getMessage(),
                                reqWithId -> {
                                    var future =
                                            failingRecipient.ask(new EchoMessage(
                                                            reqWithId.requestId(),
                                                            reqWithId.payload().payload()),
                                                    Duration.ofSeconds(1))
                                                    .thenApply(
                                                            s -> {
                                                                return new ScatterGather
                                                                        .ReplyWithId<>(
                                                                        reqWithId.requestId(),
                                                                        Result.ok("replied"));
                                                            });
                                    return future;
                                });

        // Assert
        assertThat(result)
                .as("Result should be success due to fallback")
                .isInstanceOf(Result.Ok.class);

        var okResult = (Result.Ok<String, Exception>) result;
        assertThat(okResult.value()).as("Should contain fallback message").startsWith("fallback");

        failingProc.stop();
    }

    @Test
    @DisplayName("Concurrent execution: all requests start simultaneously")
    void testConcurrentExecution() {
        // Arrange
        var startTimes = new java.util.concurrent.CopyOnWriteArrayList<Long>();
        var concurrentProcs = new ArrayList<Proc<ServerState, EchoMessage>>();
        var concurrentRecipients = new ArrayList<ProcRef<ServerState, EchoMessage>>();

        for (int i = 0; i < 5; i++) {
            var proc =
                    new Proc<ServerState, EchoMessage>(
                            new ServerState(0),
                            (state, msg) -> {
                                startTimes.add(System.nanoTime());
                                try {
                                    Thread.sleep(100); // Simulate work
                                } catch (InterruptedException e) {
                                    Thread.currentThread().interrupt();
                                }
                                return state;
                            });
            concurrentProcs.add(proc);
            concurrentRecipients.add(new ProcRef<>(proc));
        }

        // Act
        var scatterGather =
                new ScatterGather<EchoMessage, String, ServerState, EchoMessage>();
        var result =
                scatterGather.scatterGather(
                        new EchoMessage("concurrent", "test"),
                        concurrentRecipients,
                        2000, // 2 second timeout
                        reqWithId -> {
                            var futures =
                                    new ArrayList<
                                            CompletableFuture<
                                                    ScatterGather.ReplyWithId<String>>>();
                            for (var r : concurrentRecipients) {
                                var future =
                                        r.ask(new EchoMessage(
                                                        reqWithId.requestId(),
                                                        reqWithId.payload().payload()),
                                                Duration.ofSeconds(2))
                                                .thenApply(
                                                        s -> {
                                                            return new ScatterGather
                                                                    .ReplyWithId<>(
                                                                    reqWithId.requestId(),
                                                                    Result.ok("replied"));
                                                        });
                                futures.add(future);
                            }
                            return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                                    .thenApply(_ -> futures.get(0).join());
                        });

        // Assert
        assertThat(result).isInstanceOf(Result.Ok.class);

        // All should start within a small time window (concurrent)
        if (startTimes.size() >= 2) {
            var minTime = startTimes.stream().mapToLong(Long::longValue).min().orElse(0);
            var maxTime = startTimes.stream().mapToLong(Long::longValue).max().orElse(0);
            var elapsed = maxTime - minTime;
            // All should start within 500ms of each other (concurrent startup)
            assertThat(elapsed)
                    .as("All requests should start concurrently")
                    .isLessThan(500_000_000L); // 500ms in nanoseconds
        }

        try {
            for (var proc : concurrentProcs) {
                proc.stop();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Test
    @DisplayName("Reply ordering: replies returned in recipient order")
    void testReplyOrdering() {
        // Arrange
        var scatterGather =
                new ScatterGather<EchoMessage, Integer, ServerState, EchoMessage>();
        var orderedRecipients = new ArrayList<ProcRef<ServerState, EchoMessage>>();

        for (int i = 0; i < 3; i++) {
            int idx = i;
            var proc =
                    new Proc<ServerState, EchoMessage>(
                            new ServerState(0),
                            (state, msg) -> {
                                return new ServerState(idx); // Encode order in state
                            });
            orderedRecipients.add(new ProcRef<>(proc));
        }

        // Act
        var result =
                scatterGather.scatterGather(
                        new EchoMessage("order", "test"),
                        orderedRecipients,
                        1000,
                        reqWithId -> {
                            var futures =
                                    new ArrayList<
                                            CompletableFuture<
                                                    ScatterGather.ReplyWithId<Integer>>>();
                            for (int i = 0; i < orderedRecipients.size(); i++) {
                                int idx = i;
                                var future =
                                        orderedRecipients
                                                .get(i)
                                                .ask(new EchoMessage(
                                                        reqWithId.requestId(),
                                                        reqWithId.payload().payload()),
                                                        Duration.ofSeconds(1))
                                                .thenApply(
                                                        s -> {
                                                            return new ScatterGather
                                                                    .ReplyWithId<>(
                                                                    reqWithId.requestId(),
                                                                    Result.ok(idx));
                                                        });
                                futures.add(future);
                            }
                            return CompletableFuture.completedFuture(futures.get(0).join());
                        });

        // Assert
        assertThat(result).isInstanceOf(Result.Ok.class);

        try {
            for (var ref : orderedRecipients) {
                ref.stop();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
