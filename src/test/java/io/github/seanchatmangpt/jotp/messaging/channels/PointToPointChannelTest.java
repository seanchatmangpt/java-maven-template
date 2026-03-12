package io.github.seanchatmangpt.jotp.messaging.channels;

import io.github.seanchatmangpt.jotp.*;
import io.github.seanchatmangpt.jotp.messaging.Message;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.*;

/**
 * Test suite for PointToPointChannel pattern.
 * Verifies 1:1 message delivery, ordering, and state management.
 */
@DisplayName("Point-to-Point Channel Pattern")
class PointToPointChannelTest {

    static class TestState {
        final Queue<Message> received = new ConcurrentLinkedQueue<>();
        int messageCount = 0;

        TestState addMessage(Message msg) {
            received.offer(msg);
            messageCount++;
            return this;
        }
    }

    @Test
    @DisplayName("should deliver message to exactly one receiver")
    void testExactlyOneDelivery() throws InterruptedException {
        // Given: a Point-to-Point channel (receiver)
        var receiver = PointToPointChannel.createReceiver(
            state -> msg -> state.addMessage(msg),
            new TestState()
        );

        // When: sending a message
        var testMsg = Message.event("TEST_EVENT", "payload");
        PointToPointChannel.send(receiver, testMsg);

        Thread.sleep(100); // Let async processing complete

        // Then: message delivered exactly once
        var state = Proc.getState(receiver);
        assertThat(state.received).hasSize(1);
        assertThat(state.received.peek()).isEqualTo(testMsg);
    }

    @Test
    @DisplayName("should preserve message ordering")
    void testMessageOrdering() throws InterruptedException {
        // Given: a receiver and multiple messages
        var receiver = PointToPointChannel.createReceiver(
            state -> msg -> state.addMessage(msg),
            new TestState()
        );

        var messages = new Message[]{
            Message.event("EVENT_1", "first"),
            Message.event("EVENT_2", "second"),
            Message.event("EVENT_3", "third")
        };

        // When: sending messages in order
        for (var msg : messages) {
            PointToPointChannel.send(receiver, msg);
        }

        Thread.sleep(200); // Wait for processing

        // Then: messages received in same order
        var state = Proc.getState(receiver);
        assertThat(state.messageCount).isEqualTo(3);
        assertThat(state.received)
            .containsExactly(messages[0], messages[1], messages[2]);
    }

    @Test
    @DisplayName("should support state mutation across messages")
    void testStateMutation() throws InterruptedException {
        // Given: a receiver with mutable state
        class CounterState {
            int count = 0;
            List<String> events = new ArrayList<>();

            CounterState increment(String eventType) {
                count++;
                events.add(eventType);
                return this;
            }
        }

        var receiver = PointToPointChannel.createReceiver(
            state -> msg -> {
                if (msg instanceof Message.EventMsg evt) {
                    return state.increment(evt.eventType());
                }
                return state;
            },
            new CounterState()
        );

        // When: sending multiple different events
        String[] events = {"LOGIN", "PURCHASE", "LOGOUT"};
        for (String event : events) {
            PointToPointChannel.send(receiver, Message.event(event, null));
        }

        Thread.sleep(200);

        // Then: state accumulated correctly
        var state = Proc.getState(receiver);
        assertThat(state.count).isEqualTo(3);
        assertThat(state.events).containsExactly("LOGIN", "PURCHASE", "LOGOUT");
    }

    @Test
    @DisplayName("should handle different message types (sealed interface)")
    void testMessageTypeHandling() throws InterruptedException {
        // Given: receiver that handles different message types
        class TypeTrackerState {
            List<String> types = new ArrayList<>();
        }

        var receiver = PointToPointChannel.createReceiver(
            state -> msg -> {
                state.types.add(switch (msg) {
                    case Message.EventMsg _ -> "EVENT";
                    case Message.CommandMsg _ -> "COMMAND";
                    case Message.QueryMsg _ -> "QUERY";
                    case Message.DocumentMsg _ -> "DOCUMENT";
                });
                return state;
            },
            new TypeTrackerState()
        );

        // When: sending different message types
        PointToPointChannel.send(receiver, Message.event("test", null));
        PointToPointChannel.send(receiver, Message.command("cmd", null, null));
        PointToPointChannel.send(receiver, Message.query("q", null));
        PointToPointChannel.send(receiver, Message.document("doc", new byte[0]));

        Thread.sleep(200);

        // Then: all types handled
        var state = Proc.getState(receiver);
        assertThat(state.types)
            .containsExactly("EVENT", "COMMAND", "QUERY", "DOCUMENT");
    }

    @Test
    @DisplayName("should support pipeline routing")
    void testPipelineRouting() throws InterruptedException {
        // Given: a 3-stage pipeline
        class PipelineState {
            String currentValue = "";
            int stagesPassed = 0;

            PipelineState processAt(String stage) {
                currentValue = stage;
                stagesPassed++;
                return this;
            }
        }

        var initialState = new PipelineState();
        var pipeline = PointToPointChannel.createPipeline(
            initialState,
            state -> msg -> state.processAt("STAGE_1"),
            state -> msg -> state.processAt("STAGE_2"),
            state -> msg -> state.processAt("STAGE_3")
        );

        // When: routing message through pipeline
        var msg = Message.event("FLOW", null);
        PointToPointChannel.routeThrough(pipeline, msg);

        Thread.sleep(200);

        // Then: message passed through all stages
        assertThat(pipeline).hasLength(3);
        var finalState = Proc.getState(pipeline[2]);
        assertThat(finalState.stagesPassed).isEqualTo(1);
        assertThat(finalState.currentValue).isEqualTo("STAGE_3");
    }

    @Test
    @DisplayName("should have unique message IDs")
    void testMessageIdUniqueness() throws InterruptedException {
        // Given: receiver collecting messages
        var receiver = PointToPointChannel.createReceiver(
            state -> msg -> state.addMessage(msg),
            new TestState()
        );

        // When: sending multiple messages
        var messages = new Message[]{
            Message.event("E1", null),
            Message.event("E2", null),
            Message.event("E3", null)
        };

        for (var msg : messages) {
            PointToPointChannel.send(receiver, msg);
        }

        Thread.sleep(200);

        // Then: all message IDs are unique
        var state = Proc.getState(receiver);
        var ids = state.received.stream()
            .map(Message::messageId)
            .distinct()
            .count();

        assertThat(ids).isEqualTo(3);
    }

    @Test
    @DisplayName("should preserve message timestamps")
    void testMessageTimestamps() throws InterruptedException {
        // Given: receiver
        var receiver = PointToPointChannel.createReceiver(
            state -> msg -> state.addMessage(msg),
            new TestState()
        );

        // When: sending message
        long beforeTime = System.currentTimeMillis();
        var msg = Message.event("TEST", null);
        long afterTime = System.currentTimeMillis();
        PointToPointChannel.send(receiver, msg);

        Thread.sleep(100);

        // Then: timestamp is within expected range
        var state = Proc.getState(receiver);
        var received = state.received.peek();
        assertThat(received.createdAt())
            .isBetween(beforeTime, afterTime);
    }

    @Test
    @DisplayName("should isolate state between concurrent channels")
    void testStateIsolation() throws InterruptedException {
        // Given: two independent receivers
        var receiver1 = PointToPointChannel.createReceiver(
            state -> msg -> state.addMessage(msg),
            new TestState()
        );

        var receiver2 = PointToPointChannel.createReceiver(
            state -> msg -> state.addMessage(msg),
            new TestState()
        );

        // When: sending different messages to each
        var msg1 = Message.event("EVENT_1", null);
        var msg2 = Message.event("EVENT_2", null);

        PointToPointChannel.send(receiver1, msg1);
        PointToPointChannel.send(receiver2, msg2);

        Thread.sleep(200);

        // Then: each receiver has its own isolated state
        var state1 = Proc.getState(receiver1);
        var state2 = Proc.getState(receiver2);

        assertThat(state1.received).containsOnly(msg1);
        assertThat(state2.received).containsOnly(msg2);
    }
}
