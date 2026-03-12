package io.github.seanchatmangpt.jotp.test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import io.github.seanchatmangpt.jotp.ProcLib;
import org.assertj.core.api.WithAssertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Verifies OTP {@code proc_lib} startup handshake — {@code proc_lib:start_link/3}.
 *
 * <p>Armstrong: "The most dangerous moment in a supervision tree is child startup. If you don't
 * know whether the child initialized successfully before you return, you have a race condition."
 *
 * <p>OTP invariants under test:
 *
 * <ol>
 *   <li>Successful init: {@code startLink} blocks until {@code initAck()} is called, then returns
 *       {@code Ok}
 *   <li>Failed init: if the init handler throws before {@code initAck()}, returns {@code Err}
 *   <li>Timeout: if the init handler never calls {@code initAck()}, returns {@code Err} after
 *       timeout
 *   <li>Process is usable after {@code startLink} returns {@code Ok}
 * </ol>
 */
@Timeout(10)
class ProcLibTest implements WithAssertions {

    sealed interface Msg permits Msg.Inc, Msg.Ping {
        record Inc(int by) implements Msg {}

        record Ping() implements Msg {}
    }

    // ── Test 1: successful init returns Ok ────────────────────────────────

    @Test
    void startLink_successfulInit_returnsOk() {
        var initRan = new AtomicBoolean(false);

        var result =
                ProcLib.startLink(
                        0,
                        state -> {
                            initRan.set(true);
                            ProcLib.initAck(); // signal readiness
                            return state;
                        },
                        (Integer state, Msg msg) ->
                                switch (msg) {
                                    case Msg.Inc(var by) -> state + by;
                                    case Msg.Ping() -> state;
                                });

        assertThat(result).isInstanceOf(ProcLib.StartResult.Ok.class);
        assertThat(initRan).isTrue();

        var proc = ((ProcLib.StartResult.Ok<Integer, Msg>) result).proc();
        assertThat(proc.thread().isAlive()).isTrue();

        try {
            proc.stop();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ── Test 2: crash before initAck returns Err ──────────────────────────

    @Test
    void startLink_crashBeforeInitAck_returnsErr() {
        var result =
                ProcLib.startLink(
                        0,
                        state -> {
                            throw new RuntimeException("init failed intentionally");
                        },
                        (state, msg) -> state);

        assertThat(result).isInstanceOf(ProcLib.StartResult.Err.class);
        var err = (ProcLib.StartResult.Err<?, ?>) result;
        assertThat(err.reason()).isInstanceOf(RuntimeException.class);
        assertThat(err.reason().getMessage()).contains("init failed");
    }

    // ── Test 3: timeout when initAck is never called ──────────────────────

    @Test
    @Timeout(5)
    void startLink_timeoutOnMissingInitAck() {
        var result =
                ProcLib.startLink(
                        0,
                        state -> {
                            // initAck() deliberately omitted — caller will time out
                            return state;
                        },
                        (state, msg) -> state,
                        Duration.ofMillis(200) // short timeout for the test
                        );

        assertThat(result).isInstanceOf(ProcLib.StartResult.Err.class);
        var err = (ProcLib.StartResult.Err<?, ?>) result;
        assertThat(err.reason().getMessage()).contains("timed out");
    }

    // ── Test 4: process is fully functional after successful startLink ─────

    @Test
    void startLink_processIsUsableAfterOk() throws Exception {
        var result =
                ProcLib.startLink(
                        0,
                        state -> {
                            ProcLib.initAck();
                            return 100; // init sets state to 100
                        },
                        (Integer state, Msg msg) ->
                                switch (msg) {
                                    case Msg.Inc(var by) -> state + by;
                                    case Msg.Ping() -> state;
                                });

        assertThat(result).isInstanceOf(ProcLib.StartResult.Ok.class);
        var proc = ((ProcLib.StartResult.Ok<Integer, Msg>) result).proc();

        proc.tell(new Msg.Inc(5));
        var state = proc.ask(new Msg.Ping()).get();
        assertThat(state).isEqualTo(105);

        proc.stop();
    }
}
