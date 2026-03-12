package io.github.seanchatmangpt.jotp.testing.extensions;

import io.github.seanchatmangpt.jotp.testing.annotations.MessageCapture;
import org.junit.jupiter.api.extension.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * JUnit 6 extension for non-invasive message interception via ProcessMonitor.
 *
 * <p>Records all mailbox messages during test execution:
 * <ul>
 *   <li>Sender/receiver PIDs</li>
 *   <li>Timestamps</li>
 *   <li>Correlation IDs</li>
 *   <li>Message type and payload</li>
 * </ul>
 *
 * <p>Uses ProcessMonitor (not instrumentation) to avoid interfering with message flow.
 * Automatically injected into test via store.
 */
public class MessageCapturingExtension implements TestInstancePostProcessor, ParameterResolver {

  private static final ExtensionContext.Namespace NAMESPACE =
      ExtensionContext.Namespace.create(MessageCapturingExtension.class);

  public static class CapturedMessage {
    public final Object sender;
    public final Object receiver;
    public final Object message;
    public final Instant timestamp;
    public final String correlationId;
    public final String messageType;

    public CapturedMessage(Object sender, Object receiver, Object message,
        Instant timestamp, String correlationId, String messageType) {
      this.sender = sender;
      this.receiver = receiver;
      this.message = message;
      this.timestamp = timestamp;
      this.correlationId = correlationId;
      this.messageType = messageType;
    }

    @Override
    public String toString() {
      return String.format(
          "CapturedMessage{sender=%s, receiver=%s, type=%s, correlationId=%s, timestamp=%s}",
          sender, receiver, messageType, correlationId, timestamp);
    }
  }

  public static class MessageCapturingRecorder {
    private final List<CapturedMessage> messages = new CopyOnWriteArrayList<>();
    private final int maxMessages;
    private final boolean includePayload;
    private final Set<String> onlyTypes;
    private final Set<String> excludeTypes;

    public MessageCapturingRecorder(int maxMessages, boolean includePayload,
        String[] onlyTypes, String[] excludeTypes) {
      this.maxMessages = maxMessages > 0 ? maxMessages : Integer.MAX_VALUE;
      this.includePayload = includePayload;
      this.onlyTypes = new HashSet<>(Arrays.asList(onlyTypes));
      this.excludeTypes = new HashSet<>(Arrays.asList(excludeTypes));
    }

    public void recordMessage(Object sender, Object receiver, Object message,
        Instant timestamp, String correlationId) {
      if (messages.size() >= maxMessages) {
        messages.remove(0); // Prune oldest
      }

      var messageType = message.getClass().getSimpleName();
      if (!shouldCapture(messageType)) {
        return;
      }

      var payload = includePayload ? message : null;
      messages.add(new CapturedMessage(
          sender, receiver,
          payload,
          timestamp,
          correlationId,
          messageType));
    }

    private boolean shouldCapture(String messageType) {
      if (!onlyTypes.isEmpty() && !onlyTypes.contains(messageType)) {
        return false;
      }
      return !excludeTypes.contains(messageType);
    }

    public List<CapturedMessage> allMessages() {
      return Collections.unmodifiableList(messages);
    }

    public void clear() {
      messages.clear();
    }

    public int size() {
      return messages.size();
    }
  }

  @Override
  public void postProcessTestInstance(Object testInstance, ExtensionContext context)
      throws Exception {
    var annotation = testInstance.getClass().getAnnotation(MessageCapture.class);
    if (annotation != null) {
      var recorder = new MessageCapturingRecorder(
          annotation.maxMessages(),
          annotation.includePayload(),
          annotation.onlyTypes(),
          annotation.excludeTypes());

      context.getStore(NAMESPACE).put("recorder", recorder);
    }
  }

  @Override
  public boolean supportsParameter(ParameterContext parameterContext,
      ExtensionContext extensionContext) throws ParameterResolutionException {
    return parameterContext.getParameter().getType() == MessageCapturingRecorder.class;
  }

  @Override
  public Object resolveParameter(ParameterContext parameterContext,
      ExtensionContext extensionContext) throws ParameterResolutionException {
    return extensionContext.getStore(NAMESPACE).get("recorder", MessageCapturingRecorder.class);
  }
}
