package org.acme;

import java.util.concurrent.TimeUnit;
import org.assertj.core.api.WithAssertions;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Tests for {@link ProcessRegistry} — the global process name table.
 *
 * <p>Covers: register, whereis, unregister, duplicate registration guard,
 * auto-deregister on termination.
 */
@Timeout(10)
class ProcessRegistryTest implements WithAssertions {

    @BeforeEach
    @AfterEach
    void resetRegistry() {
        ProcessRegistry.reset();
    }

    // ── register + whereis ────────────────────────────────────────────────────

    @Test
    void register_thenWhereis_returnsProcess() throws Exception {
        var proc = new Proc<>(0, (state, msg) -> state);
        ProcessRegistry.register("counter", proc);

        var found = ProcessRegistry.<Integer, Object>whereis("counter");
        assertThat(found).isPresent();
        assertThat(found.get()).isSameAs(proc);
        proc.stop();
    }

    // ── whereis on unknown name returns empty ─────────────────────────────────

    @Test
    void whereis_unknownName_returnsEmpty() {
        assertThat(ProcessRegistry.whereis("nobody")).isEmpty();
    }

    // ── duplicate registration throws ─────────────────────────────────────────

    @Test
    void register_duplicate_throwsIllegalState() throws Exception {
        var proc = new Proc<>(0, (state, msg) -> state);
        ProcessRegistry.register("unique", proc);

        var other = new Proc<>(0, (state, msg) -> state);
        assertThatThrownBy(() -> ProcessRegistry.register("unique", other))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("unique");

        proc.stop();
        other.stop();
    }

    // ── explicit unregister ───────────────────────────────────────────────────

    @Test
    void unregister_removesName() throws Exception {
        var proc = new Proc<>(0, (state, msg) -> state);
        ProcessRegistry.register("temp", proc);

        ProcessRegistry.unregister("temp");

        assertThat(ProcessRegistry.whereis("temp")).isEmpty();
        proc.stop();
    }

    // ── auto-deregister on graceful stop ──────────────────────────────────────

    @Test
    void autoDeregister_onGracefulStop() throws Exception {
        var proc = new Proc<>(0, (state, msg) -> state);
        ProcessRegistry.register("dying", proc);

        proc.stop();

        Awaitility.await().atMost(5, TimeUnit.SECONDS)
                .until(() -> ProcessRegistry.whereis("dying").isEmpty());
    }

    // ── auto-deregister on crash ──────────────────────────────────────────────

    @Test
    void autoDeregister_onCrash() throws Exception {
        var proc = new Proc<Integer, String>(0, (state, msg) -> {
            throw new RuntimeException("crash");
        });
        ProcessRegistry.register("crasher", proc);

        proc.tell("boom");

        Awaitility.await().atMost(5, TimeUnit.SECONDS)
                .until(() -> ProcessRegistry.whereis("crasher").isEmpty());
    }

    // ── registered() returns snapshot of all names ───────────────────────────

    @Test
    void registered_returnsAllNames() throws Exception {
        var p1 = new Proc<>(0, (state, msg) -> state);
        var p2 = new Proc<>(0, (state, msg) -> state);
        ProcessRegistry.register("alpha", p1);
        ProcessRegistry.register("beta", p2);

        assertThat(ProcessRegistry.registered()).containsExactlyInAnyOrder("alpha", "beta");

        p1.stop();
        p2.stop();
    }

    // ── unregister safe to call even if not registered ───────────────────────

    @Test
    void unregister_nonExistentName_doesNotThrow() {
        assertThatNoException().isThrownBy(() -> ProcessRegistry.unregister("ghost"));
    }

    // ── re-register after auto-deregister succeeds ───────────────────────────

    @Test
    void reRegister_afterDeregister_succeeds() throws Exception {
        var proc = new Proc<>(0, (state, msg) -> state);
        ProcessRegistry.register("reuse", proc);
        proc.stop();

        Awaitility.await().atMost(5, TimeUnit.SECONDS)
                .until(() -> ProcessRegistry.whereis("reuse").isEmpty());

        var proc2 = new Proc<>(0, (state, msg) -> state);
        assertThatNoException().isThrownBy(() -> ProcessRegistry.register("reuse", proc2));
        proc2.stop();
    }
}
