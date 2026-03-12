package io.github.seanchatmangpt.jotp.messaging.channels;

import io.github.seanchatmangpt.jotp.*;
import io.github.seanchatmangpt.jotp.messaging.Message;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

import static org.assertj.core.api.Assertions.*;

/**
 * Comprehensive test suite for DataTypeChannel pattern.
 *
 * <p>Tests cover:
 * <ul>
 *   <li>Type-safe message routing using sealed interface pattern matching
 *   <li>Handler dispatch by message type
 *   <li>Multiple handlers for different message types
 *   <li>Pattern matching correctness
 *   <li>State accumulation across messages
 *   <li>Route registration and lookup
 *   <li>Dispatch metrics and counters
 *   <li>Type isolation and independence
 * </ul>
 */
@DisplayName("Data Type Channel Pattern")
class DataTypeChannelTest {

  // ====== Helper State Classes ======

  static class MessageTracker {
    final Queue<Message> received = new ConcurrentLinkedQueue<>();

    MessageTracker record(Message msg) {
      received.offer(msg);
      return this;
    }
  }

  static class TypeCounter {
    final Map<String, Integer> counts = new ConcurrentHashMap<>();

    TypeCounter increment(String type) {
      counts.compute(type, (k, v) -> v == null ? 1 : v + 1);
      return this;
    }

    int getCount(String type) {
      return counts.getOrDefault(type, 0);
    }
  }

  // ====== Tests ======

  @Test
  @DisplayName("should create an empty channel with no routes")
  void testCreateEmptyChannel() {
    var channel = DataTypeChannel.create();
    var state = DataTypeChannel.getState(channel);

    assertThat(state.registeredTypes()).isEmpty();
    assertThat(state.getTotalDispatched()).isEqualTo(0);
  }

  @Test
  @DisplayName("should register routes for different message types")
  void testRegisterRoutes() throws InterruptedException {
    var channel = DataTypeChannel.create();

    var cmdHandler = Proc.spawn(new MessageTracker(), s -> msg -> s.record(msg));
    var evtHandler = Proc.spawn(new MessageTracker(), s -> msg -> s.record(msg));

    DataTypeChannel.addRoute(channel, Message.CommandMsg.class, cmdHandler);
    DataTypeChannel.addRoute(channel, Message.EventMsg.class, evtHandler);

    Thread.sleep(100); // Allow route registration to propagate
    var state = DataTypeChannel.getState(channel);

    assertThat(state.hasRoute(Message.CommandMsg.class)).isTrue();
    assertThat(state.hasRoute(Message.EventMsg.class)).isTrue();
    assertThat(state.hasRoute(Message.QueryMsg.class)).isFalse();
  }

  @Test
  @DisplayName("should route CommandMsg to command handler")
  void testRouteCommandMessage() throws InterruptedException {
    var cmdHandler = Proc.spawn(new MessageTracker(), s -> msg -> s.record(msg));
    var channel = DataTypeChannel.create();

    DataTypeChannel.addRoute(channel, Message.CommandMsg.class, cmdHandler);
    Thread.sleep(100);

    var command = Message.command("UpdateProfile", "John", null);
    DataTypeChannel.dispatch(channel, command);

    Thread.sleep(200);

    var handlerState = Proc.getState(cmdHandler);
    assertThat(handlerState.received).hasSize(1);
    assertThat(handlerState.received.peek()).isEqualTo(command);
  }

  @Test
  @DisplayName("should route EventMsg to event handler")
  void testRouteEventMessage() throws InterruptedException {
    var evtHandler = Proc.spawn(new MessageTracker(), s -> msg -> s.record(msg));
    var channel = DataTypeChannel.create();

    DataTypeChannel.addRoute(channel, Message.EventMsg.class, evtHandler);
    Thread.sleep(100);

    var event = Message.event("UserRegistered", "user@example.com");
    DataTypeChannel.dispatch(channel, event);

    Thread.sleep(200);

    var handlerState = Proc.getState(evtHandler);
    assertThat(handlerState.received).hasSize(1);
    assertThat(handlerState.received.peek()).isEqualTo(event);
  }

  @Test
  @DisplayName("should route QueryMsg to query handler")
  void testRouteQueryMessage() throws InterruptedException {
    var qryHandler = Proc.spawn(new MessageTracker(), s -> msg -> s.record(msg));
    var channel = DataTypeChannel.create();

    DataTypeChannel.addRoute(channel, Message.QueryMsg.class, qryHandler);
    Thread.sleep(100);

    var query = Message.query("FindById", 42);
    DataTypeChannel.dispatch(channel, query);

    Thread.sleep(200);

    var handlerState = Proc.getState(qryHandler);
    assertThat(handlerState.received).hasSize(1);
    assertThat(handlerState.received.peek()).isEqualTo(query);
  }

  @Test
  @DisplayName("should route DocumentMsg to document handler")
  void testRouteDocumentMessage() throws InterruptedException {
    var docHandler = Proc.spawn(new MessageTracker(), s -> msg -> s.record(msg));
    var channel = DataTypeChannel.create();

    DataTypeChannel.addRoute(channel, Message.DocumentMsg.class, docHandler);
    Thread.sleep(100);

    var document = Message.document("UserData", "DocumentContent".getBytes());
    DataTypeChannel.dispatch(channel, document);

    Thread.sleep(200);

    var handlerState = Proc.getState(docHandler);
    assertThat(handlerState.received).hasSize(1);
    assertThat(handlerState.received.peek()).isEqualTo(document);
  }

  @Test
  @DisplayName("should route multiple message types to correct handlers")
  void testMultipleTypeRouting() throws InterruptedException {
    var cmdHandler = Proc.spawn(new TypeCounter(), s -> msg -> {
      if (msg instanceof Message.CommandMsg) {
        return s.increment("COMMAND");
      }
      return s;
    });

    var evtHandler = Proc.spawn(new TypeCounter(), s -> msg -> {
      if (msg instanceof Message.EventMsg) {
        return s.increment("EVENT");
      }
      return s;
    });

    var channel = DataTypeChannel.create();
    DataTypeChannel.addRoute(channel, Message.CommandMsg.class, cmdHandler);
    DataTypeChannel.addRoute(channel, Message.EventMsg.class, evtHandler);

    Thread.sleep(100);

    // Dispatch mixed messages
    DataTypeChannel.dispatch(channel, Message.command("Cmd1", null, null));
    DataTypeChannel.dispatch(channel, Message.event("Evt1", null));
    DataTypeChannel.dispatch(channel, Message.command("Cmd2", null, null));
    DataTypeChannel.dispatch(channel, Message.event("Evt2", null));
    DataTypeChannel.dispatch(channel, Message.command("Cmd3", null, null));

    Thread.sleep(300);

    var cmdState = Proc.getState(cmdHandler);
    var evtState = Proc.getState(evtHandler);

    assertThat(cmdState.getCount("COMMAND")).isEqualTo(3);
    assertThat(evtState.getCount("EVENT")).isEqualTo(2);
  }

  @Test
  @DisplayName("should track dispatch counts by message type")
  void testDispatchMetrics() throws InterruptedException {
    var channel = DataTypeChannel.create();

    var cmdHandler = Proc.spawn(new MessageTracker(), s -> msg -> s.record(msg));
    var evtHandler = Proc.spawn(new MessageTracker(), s -> msg -> s.record(msg));

    DataTypeChannel.addRoute(channel, Message.CommandMsg.class, cmdHandler);
    DataTypeChannel.addRoute(channel, Message.EventMsg.class, evtHandler);

    Thread.sleep(100);

    // Dispatch messages
    DataTypeChannel.dispatch(channel, Message.command("C1", null, null));
    DataTypeChannel.dispatch(channel, Message.event("E1", null));
    DataTypeChannel.dispatch(channel, Message.command("C2", null, null));

    Thread.sleep(200);

    var state = DataTypeChannel.getState(channel);

    assertThat(state.getDispatchCount(Message.CommandMsg.class)).isEqualTo(2);
    assertThat(state.getDispatchCount(Message.EventMsg.class)).isEqualTo(1);
    assertThat(state.getTotalDispatched()).isEqualTo(3);
  }

  @Test
  @DisplayName("should ignore messages without registered handler")
  void testUnregisteredMessageType() throws InterruptedException {
    var channel = DataTypeChannel.create();
    var evtHandler = Proc.spawn(new MessageTracker(), s -> msg -> s.record(msg));

    DataTypeChannel.addRoute(channel, Message.EventMsg.class, evtHandler);
    Thread.sleep(100);

    // Send command when only event handler is registered
    DataTypeChannel.dispatch(channel, Message.command("Test", null, null));

    Thread.sleep(200);

    var evtState = Proc.getState(evtHandler);
    assertThat(evtState.received).isEmpty();

    var channelState = DataTypeChannel.getState(channel);
    assertThat(channelState.getTotalDispatched()).isEqualTo(1);
  }

  @Test
  @DisplayName("should support pattern matching in handlers")
  void testPatternMatchingInHandler() throws InterruptedException {
    var handlerState = new TypeCounter();

    var handler = Proc.spawn(
        handlerState,
        state -> msg -> {
          return switch (msg) {
            case Message.CommandMsg _ -> state.increment("CMD");
            case Message.EventMsg _ -> state.increment("EVT");
            case Message.QueryMsg _ -> state.increment("QRY");
            case Message.DocumentMsg _ -> state.increment("DOC");
          };
        });

    var channel = DataTypeChannel.create();
    for (var msgType :
        List.of(
            Message.CommandMsg.class,
            Message.EventMsg.class,
            Message.QueryMsg.class,
            Message.DocumentMsg.class)) {
      DataTypeChannel.addRoute(channel, msgType, handler);
    }

    Thread.sleep(100);

    DataTypeChannel.dispatch(channel, Message.command("A", null, null));
    DataTypeChannel.dispatch(channel, Message.event("B", null));
    DataTypeChannel.dispatch(channel, Message.query("C", null));
    DataTypeChannel.dispatch(channel, Message.document("D", new byte[0]));

    Thread.sleep(300);

    var state = Proc.getState(handler);
    assertThat(state.getCount("CMD")).isEqualTo(1);
    assertThat(state.getCount("EVT")).isEqualTo(1);
    assertThat(state.getCount("QRY")).isEqualTo(1);
    assertThat(state.getCount("DOC")).isEqualTo(1);
  }

  @Test
  @DisplayName("should isolate state between different handlers")
  void testStateIsolationBetweenHandlers() throws InterruptedException {
    var cmdHandler = Proc.spawn(new TypeCounter(), s -> msg -> {
      if (msg instanceof Message.CommandMsg) {
        return s.increment("CMD");
      }
      return s;
    });

    var evtHandler = Proc.spawn(new TypeCounter(), s -> msg -> {
      if (msg instanceof Message.EventMsg) {
        return s.increment("EVT");
      }
      return s;
    });

    var channel = DataTypeChannel.create();
    DataTypeChannel.addRoute(channel, Message.CommandMsg.class, cmdHandler);
    DataTypeChannel.addRoute(channel, Message.EventMsg.class, evtHandler);

    Thread.sleep(100);

    DataTypeChannel.dispatch(channel, Message.command("C1", null, null));
    DataTypeChannel.dispatch(channel, Message.command("C2", null, null));
    DataTypeChannel.dispatch(channel, Message.event("E1", null));

    Thread.sleep(300);

    var cmdState = Proc.getState(cmdHandler);
    var evtState = Proc.getState(evtHandler);

    // Each handler only sees its own messages
    assertThat(cmdState.getCount("CMD")).isEqualTo(2);
    assertThat(cmdState.getCount("EVT")).isEqualTo(0);
    assertThat(evtState.getCount("EVT")).isEqualTo(1);
    assertThat(evtState.getCount("CMD")).isEqualTo(0);
  }

  @Test
  @DisplayName("should maintain message identity through routing")
  void testMessageIdentityPreserved() throws InterruptedException {
    var handler = Proc.spawn(new MessageTracker(), s -> msg -> s.record(msg));
    var channel = DataTypeChannel.create();

    DataTypeChannel.addRoute(channel, Message.EventMsg.class, handler);
    Thread.sleep(100);

    var original = Message.event("TestEvent", "payload");
    DataTypeChannel.dispatch(channel, original);

    Thread.sleep(200);

    var handlerState = Proc.getState(handler);
    var received = handlerState.received.peek();

    assertThat(received).isEqualTo(original);
    assertThat(received.messageId()).isEqualTo(original.messageId());
    assertThat(received.createdAt()).isEqualTo(original.createdAt());
  }

  @Test
  @DisplayName("should handle rapid-fire dispatches correctly")
  void testRapidDispatch() throws InterruptedException {
    var handler = Proc.spawn(new MessageTracker(), s -> msg -> s.record(msg));
    var channel = DataTypeChannel.create();

    DataTypeChannel.addRoute(channel, Message.EventMsg.class, handler);
    Thread.sleep(100);

    int count = 100;
    for (int i = 0; i < count; i++) {
      DataTypeChannel.dispatch(channel, Message.event("Event" + i, i));
    }

    Thread.sleep(500);

    var handlerState = Proc.getState(handler);
    assertThat(handlerState.received).hasSize(count);

    var channelState = DataTypeChannel.getState(channel);
    assertThat(channelState.getTotalDispatched()).isEqualTo(count);
  }

  @Test
  @DisplayName("should support route registration after channel creation")
  void testDynamicRouteRegistration() throws InterruptedException {
    var channel = DataTypeChannel.create();
    var state1 = DataTypeChannel.getState(channel);
    assertThat(state1.registeredTypes()).isEmpty();

    var cmdHandler = Proc.spawn(new MessageTracker(), s -> msg -> s.record(msg));
    DataTypeChannel.addRoute(channel, Message.CommandMsg.class, cmdHandler);

    Thread.sleep(100);

    var state2 = DataTypeChannel.getState(channel);
    assertThat(state2.hasRoute(Message.CommandMsg.class)).isTrue();
  }

  @Test
  @DisplayName("should track cumulative dispatch counts")
  void testCumulativeMetrics() throws InterruptedException {
    var channel = DataTypeChannel.create();
    var handler = Proc.spawn(new MessageTracker(), s -> msg -> s.record(msg));

    DataTypeChannel.addRoute(channel, Message.CommandMsg.class, handler);
    Thread.sleep(100);

    DataTypeChannel.dispatch(channel, Message.command("C1", null, null));
    Thread.sleep(100);

    var state1 = DataTypeChannel.getState(channel);
    assertThat(state1.getTotalDispatched()).isEqualTo(1);

    DataTypeChannel.dispatch(channel, Message.command("C2", null, null));
    DataTypeChannel.dispatch(channel, Message.command("C3", null, null));
    Thread.sleep(100);

    var state2 = DataTypeChannel.getState(channel);
    assertThat(state2.getTotalDispatched()).isEqualTo(3);
  }

  @Test
  @DisplayName("should create typed handler wrapper")
  void testTypedHandlerWrapper() throws InterruptedException {
    var handler = DataTypeChannel.createTypedHandler(
        Message.EventMsg.class,
        state -> msg -> {
          System.out.println("Event: " + msg.eventType());
          return state;
        },
        new MessageTracker());

    var event = Message.event("TestEvent", null);
    handler.send(event);

    Thread.sleep(200);

    var state = Proc.getState(handler);
    assertThat(state.received).hasSize(1);
  }

  @Test
  @DisplayName("should correctly identify sealed message variants")
  void testSealedVariantIdentification() throws InterruptedException {
    var channel = DataTypeChannel.create();

    var cmdHandler = Proc.spawn(new TypeCounter(), s -> msg -> {
      if (msg instanceof Message.CommandMsg) return s.increment("CMD");
      return s;
    });

    var evtHandler = Proc.spawn(new TypeCounter(), s -> msg -> {
      if (msg instanceof Message.EventMsg) return s.increment("EVT");
      return s;
    });

    var qryHandler = Proc.spawn(new TypeCounter(), s -> msg -> {
      if (msg instanceof Message.QueryMsg) return s.increment("QRY");
      return s;
    });

    var docHandler = Proc.spawn(new TypeCounter(), s -> msg -> {
      if (msg instanceof Message.DocumentMsg) return s.increment("DOC");
      return s;
    });

    DataTypeChannel.addRoute(channel, Message.CommandMsg.class, cmdHandler);
    DataTypeChannel.addRoute(channel, Message.EventMsg.class, evtHandler);
    DataTypeChannel.addRoute(channel, Message.QueryMsg.class, qryHandler);
    DataTypeChannel.addRoute(channel, Message.DocumentMsg.class, docHandler);

    Thread.sleep(100);

    // Create and dispatch each message type exactly once
    Message[] messages = {
      Message.command("Test", "data", null),
      Message.event("Test", "data"),
      Message.query("Test", "data"),
      Message.document("Test", new byte[] {1, 2, 3})
    };

    for (var msg : messages) {
      DataTypeChannel.dispatch(channel, msg);
    }

    Thread.sleep(300);

    assertThat(Proc.getState(cmdHandler).getCount("CMD")).isEqualTo(1);
    assertThat(Proc.getState(evtHandler).getCount("EVT")).isEqualTo(1);
    assertThat(Proc.getState(qryHandler).getCount("QRY")).isEqualTo(1);
    assertThat(Proc.getState(docHandler).getCount("DOC")).isEqualTo(1);
  }
}
