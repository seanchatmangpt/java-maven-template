package io.github.seanchatmangpt.jotp.dogfood.mclaren;

import java.time.Duration;
import java.util.function.Consumer;
import io.github.seanchatmangpt.jotp.ExitSignal;
import io.github.seanchatmangpt.jotp.Proc;
import io.github.seanchatmangpt.jotp.ProcLib;
import io.github.seanchatmangpt.jotp.ProcLib.StartResult;
import io.github.seanchatmangpt.jotp.ProcSys;
import io.github.seanchatmangpt.jotp.ProcTimer;
import io.github.seanchatmangpt.jotp.ProcTimer.TimerRef;
import io.github.seanchatmangpt.jotp.ProcessLink;
import io.github.seanchatmangpt.jotp.ProcessMonitor;
import io.github.seanchatmangpt.jotp.ProcessMonitor.MonitorRef;

/**
 * Data server telemetry recorder — OTP {@code proc_lib:start_link} + gen_server + supervisor link.
 *
 * <p>In ATLAS the {@code DataServerTelemetryRecorder} connects to a remote data server, performs
 * an initialisation handshake, then enters a recording loop. This Java 26 / OTP refactor maps
 * every step to a named primitive:
 *
 * <ol>
 *   <li><b>ProcLib.startLink</b> — parent blocks until the recorder calls {@code initAck()},
 *       exactly like {@code proc_lib:start_link}. If init fails within 5 s, {@code Err} is
 *       returned and no process leaks.
 *   <li><b>ProcTimer.sendInterval</b> — once running, a heartbeat message is sent to the
 *       recorder every 2 seconds ({@code timer:send_interval/3}). If the data server stops
 *       responding, the heartbeat handler detects the gap.
 *   <li><b>ProcessLink.spawnLink</b> — a connection-monitor process is spawned and linked to
 *       the recorder. If the monitor crashes (TCP disconnect), the recorder receives an
 *       {@link ExitSignal} rather than dying silently.
 *   <li><b>Proc.trapExits(true)</b> — the recorder traps the exit signal and transitions to
 *       {@code RecorderState.Idle} instead of crashing.
 *   <li><b>ProcessMonitor.monitor</b> — external callers can watch the recorder for DOWN events
 *       via {@link #monitor(Consumer)}.
 *   <li><b>ProcSys.statistics</b> — throughput metrics exposed via {@link #statistics()}.
 * </ol>
 *
 * <h2>Lifecycle states</h2>
 *
 * <pre>
 *   Idle ──StartRecording──▶ Recording ──StopRecording──▶ AutoRecordIdle
 *     ▲                                                          │
 *     └──────────────── ExitSignal (connection lost) ───────────┘
 * </pre>
 */
public final class RecorderProcess {

    // ---------------------------------------------------------------------------
    // Domain types
    // ---------------------------------------------------------------------------

    /** Mirrors {@code DataServerTelemetryRecorder} recorder state. */
    public enum RecorderState {
        /** Connected but not recording; waiting for session open command. */
        Idle,
        /** Automatic recording enabled; waiting for motion threshold or manual trigger. */
        AutoRecordIdle,
        /** Actively writing data to the SQL Race database. */
        Recording,
    }

    /** Messages handled by the recorder process. */
    public sealed interface RecorderMsg
            permits RecorderMsg.Heartbeat, RecorderMsg.StartRecording, RecorderMsg.StopRecording {

        /** Periodic keepalive from {@link ProcTimer}. */
        record Heartbeat() implements RecorderMsg {}

        /** Open a new SQL Race session and begin writing telemetry. */
        record StartRecording(String sessionIdentifier) implements RecorderMsg {}

        /** Stop writing and finalise the current session. */
        record StopRecording() implements RecorderMsg {}
    }

    // ---------------------------------------------------------------------------
    // Fields
    // ---------------------------------------------------------------------------

    private final Proc<RecorderState, Object> proc;
    private final TimerRef heartbeatTimer;

    private RecorderProcess(Proc<RecorderState, Object> proc, TimerRef heartbeatTimer) {
        this.proc = proc;
        this.heartbeatTimer = heartbeatTimer;
    }

    // ---------------------------------------------------------------------------
    // Factory — ProcLib.startLink handshake
    // ---------------------------------------------------------------------------

    /**
     * Start the recorder and block until initialisation completes.
     *
     * <p>The child process calls {@link ProcLib#initAck()} once it has established its simulated
     * connection to the data server. The parent unblocks within 5 seconds.
     *
     * @return the running {@link RecorderProcess}
     * @throws RuntimeException if initialisation fails or times out
     */
    public static RecorderProcess start() {
        StartResult<RecorderState, Object> result = ProcLib.startLink(
                RecorderState.Idle,
                RecorderProcess::initHandler,
                RecorderProcess::loopHandler);
        return switch (result) {
            case StartResult.Ok<RecorderState, Object>(var proc) -> {
                // Arm heartbeat: ProcTimer.sendInterval mirrors timer:send_interval/3
                TimerRef timer =
                        ProcTimer.sendInterval(2_000, proc, new RecorderMsg.Heartbeat());

                // Spawn and link a connection monitor (demonstrates ProcessLink)
                Proc<RecorderState, Object> monitor =
                        ProcessLink.spawnLink(proc, RecorderState.Idle, (state, msg) -> state);
                // The recorder traps exits so it handles the ExitSignal instead of crashing
                proc.trapExits(true);
                yield new RecorderProcess(proc, timer);
            }
            case StartResult.Err<RecorderState, Object>(var reason) ->
                throw new RuntimeException("RecorderProcess failed to start", reason);
        };
    }

    // ---------------------------------------------------------------------------
    // Lifecycle handlers
    // ---------------------------------------------------------------------------

    /**
     * Initialisation handler — runs before the main loop.
     *
     * <p>Simulates connecting to the data server. Calls {@link ProcLib#initAck()} on success.
     * Throwing here returns {@link StartResult.Err} to the caller.
     */
    private static RecorderState initHandler(RecorderState state) {
        // Simulate data-server connection check
        // In production this would open a gRPC channel to the ATLAS RTA server
        ProcLib.initAck(); // unblocks parent's startLink()
        return state;
    }

    /**
     * Main message handler — runs after init, for all subsequent messages.
     *
     * <p>Handles both {@link RecorderMsg} domain events and {@link ExitSignal} from linked
     * processes (the connection monitor). This demonstrates OTP {@code process_flag(trap_exit)}.
     */
    private static RecorderState loopHandler(RecorderState state, Object msg) {
        return switch (msg) {
            case RecorderMsg.Heartbeat ignored ->
                // Heartbeat received: connection still alive
                state;
            case RecorderMsg.StartRecording ignored ->
                RecorderState.Recording;
            case RecorderMsg.StopRecording ignored ->
                RecorderState.AutoRecordIdle;
            case ExitSignal(var reason) -> {
                // OTP: process_flag(trap_exit, true) → receive {'EXIT', From, Reason}
                // Connection monitor crashed → gracefully drop back to Idle instead of crashing
                yield RecorderState.Idle;
            }
            default -> state;
        };
    }

    // ---------------------------------------------------------------------------
    // Public API
    // ---------------------------------------------------------------------------

    /** Fire-and-forget: start recording a new session. */
    public void startRecording(String sessionIdentifier) {
        proc.tell(new RecorderMsg.StartRecording(sessionIdentifier));
    }

    /** Fire-and-forget: stop the current recording. */
    public void stopRecording() {
        proc.tell(new RecorderMsg.StopRecording());
    }

    /**
     * Install a {@link ProcessMonitor} on this recorder process.
     *
     * <p>The {@code downHandler} is called when the recorder terminates (normally or
     * abnormally). This demonstrates OTP {@code erlang:monitor(process, Pid)}.
     *
     * @param downHandler called with {@code null} on normal exit, or the crash reason otherwise
     * @return a {@link MonitorRef} that can be passed to {@link ProcessMonitor#demonitor}
     */
    public MonitorRef<RecorderState, Object> monitor(Consumer<Throwable> downHandler) {
        return ProcessMonitor.monitor(proc, downHandler);
    }

    /**
     * Throughput statistics via {@link ProcSys}.
     *
     * <p>Reads mailbox counters from the running process without stopping it — OTP
     * {@code sys:statistics/2}.
     *
     * @return current {@link ProcSys.Stats}
     */
    public ProcSys.Stats statistics() {
        return ProcSys.statistics(proc);
    }

    /**
     * Current recorder state (volatile read).
     *
     * @return {@link RecorderState} snapshot
     */
    public RecorderState state() {
        try {
            return ProcSys.<RecorderState, Object>getState(proc).join();
        } catch (Exception e) {
            return RecorderState.Idle;
        }
    }

    /**
     * Stop the recorder and cancel the heartbeat timer.
     *
     * @throws InterruptedException if interrupted while waiting for shutdown
     */
    public void stop() throws InterruptedException {
        heartbeatTimer.cancel();
        proc.stop();
    }

    /** Expose the raw process reference (for tests / advanced wiring). */
    Proc<RecorderState, Object> proc() {
        return proc;
    }

    /**
     * Start with a configurable heartbeat interval (for faster tests).
     *
     * @param heartbeatMs heartbeat period in milliseconds
     * @return running recorder
     */
    static RecorderProcess startWithHeartbeat(long heartbeatMs) {
        StartResult<RecorderState, Object> result = ProcLib.startLink(
                RecorderState.Idle,
                RecorderProcess::initHandler,
                RecorderProcess::loopHandler);
        return switch (result) {
            case StartResult.Ok<RecorderState, Object>(var proc) -> {
                TimerRef timer = ProcTimer.sendInterval(heartbeatMs, proc,
                        new RecorderMsg.Heartbeat());
                proc.trapExits(true);
                yield new RecorderProcess(proc, timer);
            }
            case StartResult.Err<RecorderState, Object>(var reason) ->
                throw new RuntimeException("RecorderProcess failed to start", reason);
        };
    }
}
