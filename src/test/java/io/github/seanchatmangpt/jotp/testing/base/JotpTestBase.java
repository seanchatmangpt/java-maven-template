package io.github.seanchatmangpt.jotp.testing.base;

import io.github.seanchatmangpt.jotp.testing.util.JotpTestHelper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * Parent class for JOTP core library tests.
 *
 * <p>Supports testing all 15 OTP primitives:
 * <ul>
 *   <li>Proc (lightweight process)</li>
 *   <li>ProcRef (stable process reference)</li>
 *   <li>Supervisor (supervision tree)</li>
 *   <li>CrashRecovery (let it crash + retry)</li>
 *   <li>StateMachine (state/event/data separation)</li>
 *   <li>ProcessLink (bilateral crash propagation)</li>
 *   <li>Parallel (structured concurrency)</li>
 *   <li>ProcessMonitor (unilateral DOWN notifications)</li>
 *   <li>ProcessRegistry (global name table)</li>
 *   <li>ProcTimer (timed message delivery)</li>
 *   <li>ExitSignal (exit signal record)</li>
 *   <li>ProcSys (introspection: get_state, suspend, resume)</li>
 *   <li>ProcLib (startup handshake)</li>
 *   <li>EventManager (typed event manager)</li>
 * </ul>
 *
 * <p>Usage:
 * <pre>{@code
 * class SupervisorTest extends JotpTestBase {
 *   @Test
 *   void testOneForOneRestart() {
 *     var supervisor = createSupervisor("ONE_FOR_ONE");
 *     // ... spawn children, test restart ...
 *     assertProcessAlive(childPid);
 *   }
 * }
 * }</pre>
 */
public abstract class JotpTestBase {

  protected JotpTestHelper helper;
  protected List<Object> spawnedProcesses;
  protected Object supervisor;

  @BeforeEach
  public void setUp() throws Exception {
    this.helper = new JotpTestHelper();
    this.spawnedProcesses = Collections.synchronizedList(new ArrayList<>());
  }

  @AfterEach
  public void tearDown() {
    // Cleanup all spawned processes
    for (var process : spawnedProcesses) {
      try {
        // Would terminate process
      } catch (Exception e) {
        // Log but continue cleanup
      }
    }
    spawnedProcesses.clear();

    // Terminate supervisor if created
    if (supervisor != null) {
      try {
        // Would terminate supervisor
      } catch (Exception e) {
        // Log but continue
      }
    }
  }

  /**
   * Create a supervisor with the specified strategy.
   */
  protected Object createSupervisor(String strategy) {
    // Implementation would create Supervisor with strategy
    // e.g., Supervisor.create("ONE_FOR_ONE", ...)
    this.supervisor = new Object();
    return supervisor;
  }

  /**
   * Spawn a child process under supervisor.
   */
  protected Object spawnChild(Object supervisor, String name) {
    var childPid = new Object(); // Would create actual process
    spawnedProcesses.add(childPid);
    return childPid;
  }

  /**
   * Assert process is alive.
   */
  protected void assertProcessAlive(Object processRef) {
    if (!helper.isProcessAlive(processRef)) {
      throw new AssertionError("Expected process to be alive: " + processRef);
    }
  }

  /**
   * Assert process is dead.
   */
  protected void assertProcessDead(Object processRef) {
    if (helper.isProcessAlive(processRef)) {
      throw new AssertionError("Expected process to be dead: " + processRef);
    }
  }

  /**
   * Get process state (via ProcSys introspection).
   */
  protected Object getProcessState(Object processRef) {
    return helper.getProcessState(processRef);
  }

  /**
   * Await process termination.
   */
  protected void awaitProcessTermination(Object processRef, long timeout, TimeUnit unit)
      throws Exception {
    helper.awaitProcessTermination(processRef, timeout, unit);
  }

  /**
   * Get mailbox size for process.
   */
  protected int getMailboxSize(Object processRef) {
    return helper.getMailboxSize(processRef);
  }

  /**
   * Register process in ProcessRegistry.
   */
  protected void registerProcess(String name, Object processRef) {
    helper.registerProcess(name, processRef);
  }

  /**
   * Look up process in ProcessRegistry.
   */
  protected Object lookupProcess(String name) {
    return helper.lookupProcess(name);
  }

  /**
   * Create a process link (bilateral crash propagation).
   */
  protected void createLink(Object process1, Object process2) {
    helper.createLink(process1, process2);
  }

  /**
   * Create a process monitor (unilateral monitoring).
   */
  protected Object createMonitor(Object processRef) {
    return helper.createMonitor(processRef);
  }

  /**
   * Default timeout for async operations (5 seconds).
   */
  protected long getDefaultTimeout(TimeUnit unit) {
    return unit.convert(5, TimeUnit.SECONDS);
  }

  /**
   * Get supervision tree structure.
   */
  protected Map<String, Object> getSupervisionTree() {
    return helper.getSupervisionTree(supervisor);
  }

  /**
   * Get crash count for a process.
   */
  protected int getCrashCount(Object processRef) {
    return helper.getCrashCount(processRef);
  }
}
