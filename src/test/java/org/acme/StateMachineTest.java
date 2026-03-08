package org.acme;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.assertj.core.api.WithAssertions;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Tests for {@link StateMachine} — the gen_statem equivalent.
 *
 * <p>The classic gen_statem example from OTP docs is a code lock:
 * locked → open (when correct code entered) → locked (when re-locked).
 *
 * <p>See: https://www.erlang.org/doc/design_principles/statem.html
 */
@Timeout(10)
class StateMachineTest implements WithAssertions {

    // ── Domain model (code lock) ─────────────────────────────────────────────

    sealed interface LockState permits LockState.Locked, LockState.Open {
        record Locked() implements LockState {}
        record Open()   implements LockState {}
    }

    sealed interface LockEvent permits LockEvent.PushButton, LockEvent.Lock {
        record PushButton(char button) implements LockEvent {}
        record Lock()                  implements LockEvent {}
    }

    record LockData(String code, String entered) {
        LockData withEntered(String e) { return new LockData(code, e); }
    }

    /** Build a code lock state machine with the given code. */
    StateMachine<LockState, LockEvent, LockData> codeLock(String code) {
        return new StateMachine<>(
                new LockState.Locked(),
                new LockData(code, ""),
                (state, event, data) -> switch (state) {
                    case LockState.Locked() -> switch (event) {
                        case LockEvent.PushButton(var b) -> {
                            var entered = data.entered() + b;
                            yield entered.equals(data.code())
                                    ? StateMachine.Transition.nextState(
                                            new LockState.Open(), data.withEntered(""))
                                    : StateMachine.Transition.keepState(
                                            data.withEntered(entered));
                        }
                        default -> StateMachine.Transition.keepState(data);
                    };
                    case LockState.Open() -> switch (event) {
                        case LockEvent.Lock() ->
                                StateMachine.Transition.nextState(
                                        new LockState.Locked(), data.withEntered(""));
                        default -> StateMachine.Transition.keepState(data);
                    };
                });
    }

    // ── Initial state ─────────────────────────────────────────────────────────

    @Test
    void initialState_isLockedWithEmptyEntered() {
        var sm = codeLock("1234");
        assertThat(sm.state()).isInstanceOf(LockState.Locked.class);
        assertThat(sm.data().entered()).isEmpty();
        assertThat(sm.isRunning()).isTrue();
        sm.send(new LockEvent.Lock()); // drain so it stops cleanly
    }

    // ── Correct code opens the lock ───────────────────────────────────────────

    @Test
    void correctCode_transitionsToOpen() throws Exception {
        var sm = codeLock("1234");
        sm.send(new LockEvent.PushButton('1'));
        sm.send(new LockEvent.PushButton('2'));
        sm.send(new LockEvent.PushButton('3'));

        // 4th button completes the code — use call() to sync on the transition
        var data = sm.call(new LockEvent.PushButton('4')).get(5, TimeUnit.SECONDS);

        assertThat(sm.state()).isInstanceOf(LockState.Open.class);
        assertThat(data.entered()).isEmpty(); // entered cleared on transition
        sm.stop();
    }

    // ── Wrong code stays locked ───────────────────────────────────────────────

    @Test
    void wrongCode_staysLocked() throws Exception {
        var sm = codeLock("1234");
        sm.send(new LockEvent.PushButton('9'));
        sm.send(new LockEvent.PushButton('9'));
        sm.send(new LockEvent.PushButton('9'));
        var data = sm.call(new LockEvent.PushButton('9')).get(5, TimeUnit.SECONDS);

        assertThat(sm.state()).isInstanceOf(LockState.Locked.class);
        sm.stop();
    }

    // ── Partial code accumulates ──────────────────────────────────────────────

    @Test
    void partialCode_accumulatesDigits() throws Exception {
        var sm = codeLock("999");
        var data = sm.call(new LockEvent.PushButton('9')).get(5, TimeUnit.SECONDS);
        assertThat(data.entered()).isEqualTo("9");

        data = sm.call(new LockEvent.PushButton('9')).get(5, TimeUnit.SECONDS);
        assertThat(data.entered()).isEqualTo("99");
        sm.stop();
    }

    // ── Re-locking ────────────────────────────────────────────────────────────

    @Test
    void lock_afterOpen_transitionsToLocked() throws Exception {
        var sm = codeLock("42");
        sm.call(new LockEvent.PushButton('4')).get(5, TimeUnit.SECONDS);
        sm.call(new LockEvent.PushButton('2')).get(5, TimeUnit.SECONDS);
        assertThat(sm.state()).isInstanceOf(LockState.Open.class);

        var data = sm.call(new LockEvent.Lock()).get(5, TimeUnit.SECONDS);
        assertThat(sm.state()).isInstanceOf(LockState.Locked.class);
        assertThat(data.entered()).isEmpty();
        sm.stop();
    }

    // ── Ignored events ────────────────────────────────────────────────────────

    @Test
    void lockEvent_inLockedState_isIgnored() throws Exception {
        var sm = codeLock("99");
        // Lock event when already locked — keepState, no transition
        var data = sm.call(new LockEvent.Lock()).get(5, TimeUnit.SECONDS);
        assertThat(sm.state()).isInstanceOf(LockState.Locked.class);
        assertThat(data.entered()).isEmpty();
        sm.stop();
    }

    // ── Transition.stop ───────────────────────────────────────────────────────

    /** A state machine that stops on a specific event. */
    sealed interface SimpleState permits SimpleState.Running, SimpleState.Done {
        record Running() implements SimpleState {}
        record Done()    implements SimpleState {}
    }

    sealed interface SimpleEvent permits SimpleEvent.Work, SimpleEvent.Quit {
        record Work(int n) implements SimpleEvent {}
        record Quit()      implements SimpleEvent {}
    }

    @Test
    void stop_transition_terminatesMachine() throws Exception {
        var sm = new StateMachine<SimpleState, SimpleEvent, Integer>(
                new SimpleState.Running(),
                0,
                (state, event, data) -> switch (event) {
                    case SimpleEvent.Work(var n) ->
                            StateMachine.Transition.keepState(data + n);
                    case SimpleEvent.Quit() ->
                            StateMachine.Transition.stop("normal");
                });

        sm.send(new SimpleEvent.Work(10));
        sm.send(new SimpleEvent.Work(5));
        // sync on the last work item
        var data = sm.call(new SimpleEvent.Work(1)).get(5, TimeUnit.SECONDS);
        assertThat(data).isEqualTo(16);

        sm.send(new SimpleEvent.Quit());

        // Wait for the machine to stop
        Awaitility.await().atMost(5, TimeUnit.SECONDS).until(() -> !sm.isRunning());
        assertThat(sm.stopReason()).isEqualTo("normal");
        assertThat(sm.isRunning()).isFalse();
    }

    @Test
    void call_afterStop_failsWithException() throws Exception {
        var sm = new StateMachine<SimpleState, SimpleEvent, Integer>(
                new SimpleState.Running(),
                0,
                (state, event, data) -> StateMachine.Transition.stop("done"));

        // Trigger stop via call — the call itself may fail
        try {
            sm.call(new SimpleEvent.Quit()).get(5, TimeUnit.SECONDS);
        } catch (ExecutionException ignored) {}

        Awaitility.await().atMost(5, TimeUnit.SECONDS).until(() -> !sm.isRunning());

        // Any subsequent call must fail
        assertThat(sm.call(new SimpleEvent.Work(1)))
                .isCompletedExceptionally();
    }

    // ── nextState vs keepState data ───────────────────────────────────────────

    sealed interface NSState permits NSState.A, NSState.B {
        record A() implements NSState {}

        record B() implements NSState {}
    }

    sealed interface NSEvent permits NSEvent.Go {
        record Go() implements NSEvent {}
    }

    sealed interface KSState permits KSState.A {
        record A() implements KSState {}
    }

    sealed interface KSEvent permits KSEvent.Inc {
        record Inc() implements KSEvent {}
    }

    sealed interface ConcState permits ConcState.On {
        record On() implements ConcState {}
    }

    sealed interface ConcEvent permits ConcEvent.Inc {
        record Inc() implements ConcEvent {}
    }

    @Test
    void nextState_updatesStateAndData() throws Exception {
        var sm =
                new StateMachine<NSState, NSEvent, String>(
                        new NSState.A(),
                        "init",
                        (state, event, data) ->
                                switch (state) {
                                    case NSState.A() ->
                                            StateMachine.Transition.nextState(
                                                    new NSState.B(), "transitioned");
                                    case NSState.B() -> StateMachine.Transition.keepState(data);
                                });

        var data = sm.call(new NSEvent.Go()).get(5, TimeUnit.SECONDS);
        assertThat(sm.state()).isInstanceOf(NSState.B.class);
        assertThat(data).isEqualTo("transitioned");
        sm.stop();
    }

    @Test
    void keepState_updatesDataOnly() throws Exception {
        var sm =
                new StateMachine<KSState, KSEvent, Integer>(
                        new KSState.A(),
                        0,
                        (state, event, data) -> StateMachine.Transition.keepState(data + 1));

        sm.call(new KSEvent.Inc()).get(5, TimeUnit.SECONDS);
        var data = sm.call(new KSEvent.Inc()).get(5, TimeUnit.SECONDS);
        assertThat(sm.state()).isInstanceOf(KSState.A.class); // state unchanged
        assertThat(data).isEqualTo(2);
        sm.stop();
    }

    // ── Concurrency: many senders, one serialized machine ────────────────────

    @Test
    void concurrentSend_isSerializedByMailbox() throws Exception {
        var sm =
                new StateMachine<ConcState, ConcEvent, Integer>(
                        new ConcState.On(),
                        0,
                        (state, event, data) -> StateMachine.Transition.keepState(data + 1));

        int senderCount = 50;
        var threads = new Thread[senderCount];
        for (int i = 0; i < senderCount; i++) {
            threads[i] = Thread.ofVirtual().start(() -> sm.send(new ConcEvent.Inc()));
        }
        for (var t : threads) t.join();

        // Drain with a final call to sync
        sm.call(new ConcEvent.Inc()).get(5, TimeUnit.SECONDS);

        // Must be exactly senderCount + 1 (the final sync call)
        assertThat(sm.data()).isEqualTo(senderCount + 1);
        sm.stop();
    }

    // ── Graceful stop ─────────────────────────────────────────────────────────

    @Test
    void stop_shutsDownCleanly() throws Exception {
        var sm = codeLock("000");
        assertThat(sm.isRunning()).isTrue();
        sm.stop();
        assertThat(sm.isRunning()).isFalse();
    }

    // ── Transition.stop via call fails the future ─────────────────────────────

    @Test
    void stop_transition_viaCall_failsFuture() {
        var sm = new StateMachine<SimpleState, SimpleEvent, Integer>(
                new SimpleState.Running(),
                0,
                (state, event, data) -> switch (event) {
                    case SimpleEvent.Quit() -> StateMachine.Transition.stop("crash");
                    case SimpleEvent.Work(var n) -> StateMachine.Transition.keepState(data + n);
                });

        var future = sm.call(new SimpleEvent.Quit());
        assertThatThrownBy(() -> future.get(5, TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class)
                .cause()
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("crash");
    }
}
