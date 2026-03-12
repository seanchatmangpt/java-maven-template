package io.github.seanchatmangpt.jotp.test.patterns;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import io.github.seanchatmangpt.jotp.EventManager;
import io.github.seanchatmangpt.jotp.Proc;
import io.github.seanchatmangpt.jotp.ProcSys;
import io.github.seanchatmangpt.jotp.Supervisor;
import io.github.seanchatmangpt.jotp.Supervisor.Strategy;
import org.assertj.core.api.WithAssertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

/**
 * Vaughn Vernon's Reactive Messaging Foundation Patterns with JOTP.
 * Tests: Message Channel, Command, Document, Event, Request-Reply,
 * Return Address, Correlation ID, Message Sequence, Expiration, Format Indicator
 */
@Timeout(60)
@Execution(ExecutionMode.SAME_THREAD)
@DisplayName("Reactive Messaging Foundation Patterns")
class ReactiveMessagingFoundationPatternsTest implements WithAssertions {

    // Class-level sealed interface (not local to methods)
    sealed interface Msg permits Msg.Inc, Msg.Reset, Msg.Get, Msg.Doc, Msg.Evt, Msg.Seq, Msg.Req {
        record Inc(int by) implements Msg {}
        record Reset() implements Msg {}
        record Get() implements Msg {}
        record Doc(String payload, int version) implements Msg {}
        record Evt(String type, String source) implements Msg {}
        record Seq(int num, String payload) implements Msg {}
        record Req(UUID id, String query, CompletableFuture<String> replyTo) implements Msg {}
    }

    record State(int counter, List<String> log, int lastSeq) {
        static State initial() { return new State(0, new ArrayList<>(), -1); }
    }

    private static State handler(State s, Msg msg) {
        return switch (msg) {
            case Msg.Inc(int by) -> new State(s.counter() + by, s.log(), s.lastSeq());
            case Msg.Reset _ -> State.initial();
            case Msg.Get _ -> s;
            case Msg.Doc(String payload, int version) -> {
                var log = new ArrayList<>(s.log());
                log.add("doc@" + version + ":" + payload);
                yield new State(s.counter(), log, s.lastSeq());
            }
            case Msg.Evt(String type, String source) -> {
                var log = new ArrayList<>(s.log());
                log.add("evt:" + type + "@" + source);
                yield new State(s.counter(), log, s.lastSeq());
            }
            case Msg.Seq(int num, String payload) -> {
                if (num != s.lastSeq() + 1) throw new IllegalStateException("Sequence gap at " + num);
                var log = new ArrayList<>(s.log());
                log.add("seq@" + num + ":" + payload);
                yield new State(s.counter(), log, num);
            }
            case Msg.Req(UUID id, String query, CompletableFuture<String> replyTo) -> {
                replyTo.complete("processed: " + query + " (counter=" + s.counter() + ")");
                yield s;
            }
        };
    }

    @Nested
    @DisplayName("1. Message Channel Pattern")
    class MessageChannelPattern {
        @Test void procAsMessageChannel() throws Exception {
            var channel = new Proc<>(State.initial(), ReactiveMessagingFoundationPatternsTest::handler);
            channel.tell(new Msg.Inc(10));
            channel.tell(new Msg.Inc(5));
            Thread.sleep(50);
            var state = channel.ask(new Msg.Get()).get(2, TimeUnit.SECONDS);
            assertThat(state.counter()).isEqualTo(15);
            channel.stop();
        }
    }

    @Nested
    @DisplayName("2. Command Message Pattern")
    class CommandMessagePattern {
        @Test void commandTriggersAction() throws Exception {
            var channel = new Proc<>(State.initial(), ReactiveMessagingFoundationPatternsTest::handler);
            channel.tell(new Msg.Inc(42));
            Thread.sleep(50);
            var state = channel.ask(new Msg.Get()).get(2, TimeUnit.SECONDS);
            assertThat(state.counter()).isEqualTo(42);
            channel.stop();
        }
    }

    @Nested
    @DisplayName("3. Document Message Pattern")
    class DocumentMessagePattern {
        @Test void documentTransfersState() throws Exception {
            var channel = new Proc<>(State.initial(), ReactiveMessagingFoundationPatternsTest::handler);
            channel.tell(new Msg.Doc("user:john", 1));
            channel.tell(new Msg.Doc("user:jane", 2));
            Thread.sleep(50);
            var state = channel.ask(new Msg.Get()).get(2, TimeUnit.SECONDS);
            assertThat(state.log()).hasSize(2);
            channel.stop();
        }
    }

    @Nested
    @DisplayName("4. Event Message Pattern")
    class EventMessagePattern {
        @Test void eventNotifiesSubscribers() throws Exception {
            var received = new AtomicInteger(0);
            var em = EventManager.<Msg.Evt>start();
            em.addHandler(e -> received.incrementAndGet());
            for (int i = 0; i < 10; i++) em.notify(new Msg.Evt("UserCreated", "svc"));
            await().atMost(Duration.ofSeconds(2)).until(() -> received.get() == 10);
            em.stop();
        }
    }

    @Nested
    @DisplayName("5. Request-Reply Pattern")
    class RequestReplyPattern {
        @Test void askProvidesRequestReply() throws Exception {
            var channel = new Proc<>(State.initial(), ReactiveMessagingFoundationPatternsTest::handler);
            var result = channel.ask(new Msg.Inc(100)).get(2, TimeUnit.SECONDS);
            assertThat(result.counter()).isEqualTo(100);
            channel.stop();
        }
    }

    @Nested
    @DisplayName("6. Return Address Pattern")
    class ReturnAddressPattern {
        @Test void messageContainsReturnAddress() throws Exception {
            var channel = new Proc<>(State.initial(), ReactiveMessagingFoundationPatternsTest::handler);
            var replyFuture = new CompletableFuture<String>();
            channel.tell(new Msg.Req(UUID.randomUUID(), "getStatus", replyFuture));
            var reply = replyFuture.get(2, TimeUnit.SECONDS);
            assertThat(reply).contains("processed");
            channel.stop();
        }
    }

    @Nested
    @DisplayName("7. Correlation Identifier Pattern")
    class CorrelationIdentifierPattern {
        @Test void correlationIdMatchesRequestResponse() throws Exception {
            var channel = new Proc<>(State.initial(), ReactiveMessagingFoundationPatternsTest::handler);
            var id1 = UUID.randomUUID();
            var id2 = UUID.randomUUID();
            var reply1 = new CompletableFuture<String>();
            var reply2 = new CompletableFuture<String>();
            channel.tell(new Msg.Req(id1, "query1", reply1));
            channel.tell(new Msg.Req(id2, "query2", reply2));
            assertThat(reply1.get(2, TimeUnit.SECONDS)).contains("query1");
            assertThat(reply2.get(2, TimeUnit.SECONDS)).contains("query2");
            channel.stop();
        }
    }

    @Nested
    @DisplayName("8. Message Sequence Pattern")
    class MessageSequencePattern {
        @Test void messagesProcessedInSequenceOrder() throws Exception {
            var channel = new Proc<>(State.initial(), ReactiveMessagingFoundationPatternsTest::handler);
            for (int i = 0; i < 50; i++) channel.tell(new Msg.Seq(i, "payload-" + i));
            Thread.sleep(200);
            var state = channel.ask(new Msg.Get()).get(2, TimeUnit.SECONDS);
            assertThat(state.lastSeq()).isEqualTo(49);
            channel.stop();
        }
        @Test void sequenceGapCausesError() throws Exception {
            var channel = new Proc<>(State.initial(), ReactiveMessagingFoundationPatternsTest::handler);
            channel.tell(new Msg.Seq(0, "first"));
            channel.tell(new Msg.Seq(2, "gap!"));
            Thread.sleep(100);
            assertThat(channel.lastError).isNotNull();
            channel.stop();
        }
    }

    @Nested
    @DisplayName("9. Message Expiration Pattern")
    class MessageExpirationPattern {
        @Test void askWithTimeoutExpires() throws Exception {
            var channel = new Proc<>(State.initial(), (State s, Msg msg) -> {
                try { Thread.sleep(500); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                return s;
            });
            var future = channel.ask(new Msg.Get(), Duration.ofMillis(50));
            assertThatThrownBy(() -> future.get(1, TimeUnit.SECONDS))
                    .hasCauseInstanceOf(java.util.concurrent.TimeoutException.class);
            channel.stop();
        }
    }

    @Nested
    @DisplayName("10. Format Indicator Pattern")
    class FormatIndicatorPattern {
        @Test void sealedInterfaceProvidesTypeSafety() throws Exception {
            var channel = new Proc<>(State.initial(), ReactiveMessagingFoundationPatternsTest::handler);
            channel.tell(new Msg.Inc(1));
            channel.tell(new Msg.Doc("data", 1));
            channel.tell(new Msg.Evt("type", "source"));
            Thread.sleep(100);
            var stats = ProcSys.statistics(channel);
            assertThat(stats.messagesIn()).isEqualTo(3);
            channel.stop();
        }
        @Test void patternMatchingIsExhaustive() {
            var state = State.initial();
            assertThatCode(() -> {
                handler(state, new Msg.Inc(1));
                handler(state, new Msg.Reset());
                handler(state, new Msg.Get());
                handler(state, new Msg.Doc("x", 1));
                handler(state, new Msg.Evt("e", "s"));
                handler(state, new Msg.Seq(0, "x"));
                handler(state, new Msg.Req(UUID.randomUUID(), "q", new CompletableFuture<>()));
            }).doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("Integration")
    class Integration {
        @Test void fullRequestResponseCycle() throws Exception {
            var sup = new Supervisor("foundation-sv", Strategy.ONE_FOR_ONE, 10, Duration.ofMinutes(5));
            var channel = sup.supervise("channel", State.initial(), ReactiveMessagingFoundationPatternsTest::handler);
            channel.tell(new Msg.Inc(50));
            channel.tell(new Msg.Doc("config:v2", 2));
            for (int i = 0; i < 10; i++) channel.tell(new Msg.Seq(i, "seq-" + i));
            var replyFuture = new CompletableFuture<String>();
            channel.tell(new Msg.Req(UUID.randomUUID(), "finalQuery", replyFuture));
            var reply = replyFuture.get(5, TimeUnit.SECONDS);
            assertThat(reply).contains("processed");
            sup.shutdown();
        }
    }
}
