package io.github.seanchatmangpt.jotp.testing.annotations;

import java.lang.annotation.*;

/**
 * Marks a test for JOTP core library primitives.
 *
 * <p>Tests the 15 OTP primitives:
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
 * @JotpTest(primitive = "Supervisor")
 * class SupervisorTest extends JotpTestBase {
 *   @Test
 *   void testOneForOneRestart() { ... }
 * }
 * }</pre>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
@Documented
public @interface JotpTest {

  /**
   * JOTP primitive being tested.
   *
   * @return primitive name (e.g., "Proc", "Supervisor", "StateMachine")
   */
  String primitive() default "";

  /**
   * Optional list of primitive names for multi-primitive tests.
   *
   * @return array of primitives
   */
  String[] primitives() default {};

  /**
   * Enable crash recovery testing (let it crash + supervised restart).
   *
   * @return true to test failure scenarios
   */
  boolean testCrashRecovery() default false;

  /**
   * Enable state extraction (via ProcSys introspection).
   *
   * @return true to test get_state(), suspend(), resume()
   */
  boolean testStateIntrospection() default false;

  /**
   * Enable process link testing (bilateral crash propagation).
   *
   * @return true to test link/unlink
   */
  boolean testProcessLinks() default false;

  /**
   * Enable process monitor testing.
   *
   * @return true to test monitor/demonitor
   */
  boolean testProcessMonitors() default false;

  /**
   * Enable ProcessRegistry testing.
   *
   * @return true to test register/unregister/whereis
   */
  boolean testProcessRegistry() default false;
}
