package org.acme.test;

import static org.awaitility.Awaitility.await;

import java.time.Duration;
import org.acme.Proc;
import org.acme.ProcessRegistry;
import org.assertj.core.api.WithAssertions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Verifies OTP process-registry semantics in Java 26.
 *
 * <p>Key OTP invariants under test:
 *
 * <ol>
 *   <li>{@code register/whereis} — basic registration and lookup
 *   <li>Duplicate registration throws
 *   <li>Auto-deregistration on process death (any reason)
 *   <li>Explicit {@code unregister} removes name without stopping process
 *   <li>{@code registered()} returns snapshot of current names
 * </ol>
 */
@Timeout(10)
class ProcessRegistryTest implements WithAssertions {

    sealed interface Msg permits Msg.Ping, Msg.Crash {
        record Ping() implements Msg {}

        record Crash() implements Msg {}
    }

    static Proc<Integer, Msg> counter() {
        return new Proc<>(
                0,
                (state, msg) ->
                        switch (msg) {
                            case Msg.Ping() -> state + 1;
                            case Msg.Crash() -> throw new RuntimeException("BOOM");
                        });
    }

    @AfterEach
    void cleanup() {
        ProcessRegistry.reset();
    }

    // -------------------------------------------------------------------------
    // 1. register + whereis basic round-trip
    // -------------------------------------------------------------------------

    @Test
    void register_whereisReturnsProc() throws InterruptedException {
        var proc = counter();
        ProcessRegistry.register("counter", proc);

        var found = ProcessRegistry.<Integer, Msg>whereis("counter");

        assertThat(found).isPresent().contains(proc);

        proc.stop();
    }

    @Test
    void whereis_unknownName_returnsEmpty() {
        assertThat(ProcessRegistry.whereis("no-such-process")).isEmpty();
    }

    // -------------------------------------------------------------------------
    // 2. Duplicate registration throws
    // -------------------------------------------------------------------------

    @Test
    void register_duplicate_throws() throws InterruptedException {
        var a = counter();
        var b = counter();
        ProcessRegistry.register("x", a);

        assertThatThrownBy(() -> ProcessRegistry.register("x", b))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("x");

        a.stop();
        b.stop();
    }

    // -------------------------------------------------------------------------
    // 3. Auto-deregistration on abnormal exit
    // -------------------------------------------------------------------------

    @Test
    void autoDeregister_onCrash() {
        var proc = counter();
        ProcessRegistry.register("crasher", proc);

        proc.tell(new Msg.Crash());

        await().atMost(Duration.ofSeconds(3))
                .until(() -> ProcessRegistry.whereis("crasher").isEmpty());
    }

    // -------------------------------------------------------------------------
    // 4. Auto-deregistration on normal stop
    // -------------------------------------------------------------------------

    @Test
    void autoDeregister_onNormalStop() throws InterruptedException {
        var proc = counter();
        ProcessRegistry.register("stopper", proc);

        proc.stop();

        await().atMost(Duration.ofSeconds(3))
                .until(() -> ProcessRegistry.whereis("stopper").isEmpty());
    }

    // -------------------------------------------------------------------------
    // 5. Explicit unregister removes name; process keeps running
    // -------------------------------------------------------------------------

    @Test
    void unregister_removesName_processStillAlive() throws InterruptedException {
        var proc = counter();
        ProcessRegistry.register("alive", proc);

        ProcessRegistry.unregister("alive");

        assertThat(ProcessRegistry.whereis("alive")).isEmpty();

        // process still alive — can accept messages
        var count = proc.ask(new Msg.Ping()).join();
        assertThat(count).isEqualTo(1);

        proc.stop();
    }

    // -------------------------------------------------------------------------
    // 6. registered() returns snapshot of all current names
    // -------------------------------------------------------------------------

    @Test
    void registered_returnsAllNames() throws InterruptedException {
        var a = counter();
        var b = counter();
        ProcessRegistry.register("alpha", a);
        ProcessRegistry.register("beta", b);

        var names = ProcessRegistry.registered();

        assertThat(names).contains("alpha", "beta");

        a.stop();
        b.stop();
    }

    // -------------------------------------------------------------------------
    // 7. Name can be reused after process dies
    // -------------------------------------------------------------------------

    @Test
    void register_nameReusableAfterDeath() throws InterruptedException {
        var first = counter();
        ProcessRegistry.register("reusable", first);
        first.stop();

        await().atMost(Duration.ofSeconds(3))
                .until(() -> ProcessRegistry.whereis("reusable").isEmpty());

        // Now register a new proc under the same name
        var second = counter();
        ProcessRegistry.register("reusable", second);
        assertThat(ProcessRegistry.<Integer, Msg>whereis("reusable")).isPresent().contains(second);

        second.stop();
    }
}
