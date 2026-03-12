package io.github.seanchatmangpt.jotp.dogfood.mclaren;

import java.util.function.Consumer;
import io.github.seanchatmangpt.jotp.Proc;
import io.github.seanchatmangpt.jotp.ProcessMonitor;
import io.github.seanchatmangpt.jotp.ProcessMonitor.MonitorRef;

/**
 * Session and parameter process monitor — OTP {@code ProcessMonitor} (unilateral DOWN
 * notification) mapped to ATLAS session lifecycle observation.
 *
 * <p>In ATLAS, an analyst workstation or remote factory must detect when:
 *
 * <ul>
 *   <li>A {@link ParameterDataAccess} process crashes (hardware fault, data corruption)
 *   <li>The live session recorder disconnects from the data server
 * </ul>
 *
 * <p>OTP's {@code erlang:monitor(process, Pid)} — implemented here as
 * {@link ProcessMonitor#monitor(Proc, Consumer)} — installs a unilateral DOWN callback. Unlike
 * {@link org.acme.ProcessLink}, a monitor does <em>not</em> crash the monitoring side if the
 * target crashes. This is correct for display plugins and analytics dashboards: the dashboard
 * should survive a parameter process restart.
 *
 * <p>Real Erlang equivalent:
 *
 * <pre>
 *   Ref = erlang:monitor(process, ParameterPid),
 *   receive
 *     {'DOWN', Ref, process, ParameterPid, Reason} ->
 *       io:format("Parameter ~p crashed: ~p~n", [Id, Reason])
 *   end.
 * </pre>
 *
 * <p>Java 26 equivalent:
 *
 * <pre>{@code
 * var monitorRef = SessionMonitor.watchParameter(vCarProc, reason -> {
 *     if (reason == null) {
 *         log.info("vCar:Chassis process stopped normally");
 *     } else {
 *         log.warn("vCar:Chassis crashed: {}", reason.getMessage());
 *     }
 * });
 *
 * // Later, cancel the monitor when the display plugin is closed:
 * SessionMonitor.cancel(monitorRef);
 * }</pre>
 */
public final class SessionMonitor {

    private SessionMonitor() {}

    /**
     * Watch a {@link ParameterDataAccess} process for DOWN events.
     *
     * <p>The {@code downHandler} is called on the target's virtual thread when the process
     * terminates. {@code reason == null} means normal stop; non-null means crash.
     *
     * <p>The monitoring side is NOT affected — this is a unilateral observation, not a link.
     *
     * @param proc        the parameter process to monitor
     * @param downHandler callback invoked on termination
     * @return a {@link MonitorRef} that can be passed to {@link #cancel}
     */
    public static MonitorRef<ParameterDataAccess.State, PdaMsg> watchParameter(
            Proc<ParameterDataAccess.State, PdaMsg> proc, Consumer<Throwable> downHandler) {
        return ProcessMonitor.monitor(proc, downHandler);
    }

    /**
     * Watch a recorder process for DOWN events.
     *
     * <p>Useful for ATLAS display plugins that need to show a "disconnected" banner when the
     * data server connection is lost.
     *
     * @param recorder    the recorder process to monitor
     * @param downHandler callback invoked on termination
     * @return a {@link MonitorRef} that can be cancelled
     */
    public static MonitorRef<RecorderProcess.RecorderState, Object> watchRecorder(
            RecorderProcess recorder, Consumer<Throwable> downHandler) {
        return ProcessMonitor.monitor(recorder.proc(), downHandler);
    }

    /**
     * Cancel a previously installed monitor — mirrors {@code erlang:demonitor(Ref)}.
     *
     * <p>After cancellation the {@code downHandler} will not be invoked even if the target
     * process subsequently terminates.
     *
     * @param ref the monitor reference to cancel
     */
    public static void cancel(MonitorRef<?, ?> ref) {
        ProcessMonitor.demonitor(ref);
    }
}
