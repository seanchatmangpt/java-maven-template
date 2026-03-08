package org.acme.test;

import java.util.concurrent.TimeUnit;
import org.acme.Actor;
import org.assertj.core.api.WithAssertions;
import org.junit.jupiter.api.Test;

class ActorTest implements WithAssertions {

    // Sealed message hierarchy — exhaustive pattern matching, zero virtual dispatch
    sealed interface CounterMsg permits CounterMsg.Increment, CounterMsg.Reset, CounterMsg.Noop {
        record Increment(int by) implements CounterMsg {}

        record Reset() implements CounterMsg {}

        record Noop() implements CounterMsg {}
    }

    private static int handleCounter(int state, CounterMsg msg) {
        return switch (msg) {
            case CounterMsg.Increment(var by) -> state + by;
            case CounterMsg.Reset() -> 0;
            case CounterMsg.Noop() -> state;
        };
    }

    @Test
    void tellAndAsk() throws Exception {
        var actor = new Actor<>(0, ActorTest::handleCounter);

        actor.tell(new CounterMsg.Increment(10));
        actor.tell(new CounterMsg.Increment(5));

        var state = actor.ask(new CounterMsg.Noop()).get(1, TimeUnit.SECONDS);
        assertThat(state).isEqualTo(15);

        actor.stop();
    }

    @Test
    void resetReturnsToZero() throws Exception {
        var actor = new Actor<>(0, ActorTest::handleCounter);

        actor.tell(new CounterMsg.Increment(100));
        actor.tell(new CounterMsg.Reset());

        var state = actor.ask(new CounterMsg.Noop()).get(1, TimeUnit.SECONDS);
        assertThat(state).isZero();

        actor.stop();
    }

    @Test
    void manyMessagesFromMultipleThreads() throws Exception {
        var actor = new Actor<>(0, ActorTest::handleCounter);
        var senders = new Thread[10];
        for (int i = 0; i < senders.length; i++) {
            senders[i] =
                    Thread.ofVirtual()
                            .start(
                                    () -> {
                                        for (int j = 0; j < 100; j++) {
                                            actor.tell(new CounterMsg.Increment(1));
                                        }
                                    });
        }
        for (var s : senders) s.join();

        var state = actor.ask(new CounterMsg.Noop()).get(2, TimeUnit.SECONDS);
        assertThat(state).isEqualTo(1000);

        actor.stop();
    }
}
