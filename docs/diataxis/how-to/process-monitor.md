# How to Monitor Processes for Unilateral DOWN Notifications

## Problem

You need to know when a process terminates (normally or abnormally) without
stopping your own process when it does.

## Solution

Use `ProcessMonitor.monitor(ref)` to get a `MonitorRef`. When the monitored
process exits, a DOWN notification fires — either to a callback or by blocking
on `awaitDown()`.

## Prerequisites

- `org.acme.ProcessMonitor`, `org.acme.Proc`, `org.acme.ProcRef` on the module path
- Java 26 with `--enable-preview`
- Distinguishes from `ProcessLink`: monitoring does NOT kill the monitoring side

---

## Step 1 — Start a process to monitor

```java
import org.acme.Proc;
import org.acme.ProcRef;

ProcRef<String, String> worker = Proc.of("running", (state, msg) -> msg);
```

---

## Step 2 — Attach a monitor

```java
import org.acme.ProcessMonitor;

ProcessMonitor monitor = ProcessMonitor.monitor(worker);
```

You can monitor the same process from multiple sites independently.

---

## Step 3 — Block until the process exits

```java
// Blocks the calling thread until the monitored process stops
ProcessMonitor.DownSignal down = monitor.awaitDown();

System.out.println("Process exited. Cause: " + down.cause());
```

`DownSignal.cause()` returns `null` for a normal exit and a `Throwable` for an
abnormal one.

---

## Step 4 — Use a timeout with awaitDown

```java
import java.util.concurrent.TimeUnit;

var down = monitor.awaitDown(5, TimeUnit.SECONDS);
if (down == null) {
    System.out.println("Process is still alive after 5 s");
} else {
    System.out.println("Process exited: " + down.cause());
}
```

---

## Step 5 — React to DOWN in a process mailbox

If the monitoring side is itself a `Proc`, configure it so DOWN signals are
delivered to its mailbox:

```java
ProcRef<String, Object> observer = Proc.of("watching", (state, msg) -> {
    if (msg instanceof ProcessMonitor.DownSignal down) {
        System.out.println("DOWN received from: " + down.from());
        return "done";
    }
    return state;
});

ProcessMonitor.monitor(worker, observer); // delivers DownSignal to observer's mailbox
```

---

## Step 6 — Cancel a monitor

```java
monitor.cancel();
```

After cancellation, no DOWN notification is fired even if the monitored process
exits.

---

## Step 7 — Monitor with Awaitility in tests

```java
import static org.awaitility.Awaitility.await;
import static java.util.concurrent.TimeUnit.SECONDS;

var monitor = ProcessMonitor.monitor(worker);

worker.stop();

await().atMost(2, SECONDS).until(() -> monitor.isDown());
assertThat(monitor.downSignal().cause()).isNull(); // normal exit
```

---

## Verification

```java
var ref    = Proc.of("alive", (s, m) -> m);
var mon    = ProcessMonitor.monitor(ref);

assertThat(mon.isDown()).isFalse();

ref.stop();

await().atMost(1, SECONDS).until(mon::isDown);
assertThat(mon.downSignal().cause()).isNull();
```

---

## Troubleshooting

**`awaitDown()` blocks indefinitely.**
The monitored process is still alive. Either stop the process from another
thread or use the timeout overload: `monitor.awaitDown(N, SECONDS)`.

**DOWN is delivered but `cause()` is unexpectedly non-null.**
The process threw an uncaught exception before stopping. Inspect the cause to
diagnose the crash.

**I want both sides to stop when one crashes.**
Use `ProcessLink` instead. `ProcessMonitor` is strictly observational and never
kills the monitoring side.

**Monitor fires immediately on registration.**
The monitored process was already stopped before `monitor(ref)` was called.
`ProcessMonitor` fires immediately in this case to avoid a race condition.
