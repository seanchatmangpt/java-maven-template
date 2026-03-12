package io.github.seanchatmangpt.jotp.testing.annotations;

import java.lang.annotation.*;

/**
 * Non-invasive message interception via ProcessMonitor.
 *
 * <p>Records all mailbox messages with:
 * <ul>
 *   <li>Timestamps</li>
 *   <li>Sender/receiver PIDs</li>
 *   <li>Correlation IDs</li>
 *   <li>Message type and payload</li>
 * </ul>
 *
 * <p>Does NOT interfere with message flow (unlike spies/mocks).
 *
 * <p>Usage:
 * <pre>{@code
 * @MessageCapture(captureAll = true)
 * class RouterTest {
 *   @Inject MessageCapture capture;
 *
 *   @Test
 *   void testMessageFlow() {
 *     send(router, msg1);
 *     send(router, msg2);
 *
 *     var allMessages = capture.allMessages();
 *     assertThat(allMessages)
 *       .filteredOn(m -> m.type() == RESULT)
 *       .hasSize(2);
 *   }
 * }
 * }</pre>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
@Documented
public @interface MessageCapture {

  /**
   * Capture all messages during test.
   *
   * @return true to capture everything
   */
  boolean captureAll() default true;

  /**
   * Only capture specific message types (empty = all).
   *
   * @return array of class names to filter
   */
  String[] onlyTypes() default {};

  /**
   * Exclude specific message types (empty = none).
   *
   * @return array of class names to exclude
   */
  String[] excludeTypes() default {};

  /**
   * Maximum captured messages before pruning.
   *
   * @return max message count (0 = unlimited)
   */
  int maxMessages() default 1000;

  /**
   * Include payload in captured data (memory intensive).
   *
   * @return true to capture payloads
   */
  boolean includePayload() default true;

  /**
   * Enable message tracing (includes call stacks).
   *
   * @return true to trace sender locations
   */
  boolean traceStackTrace() default false;
}
