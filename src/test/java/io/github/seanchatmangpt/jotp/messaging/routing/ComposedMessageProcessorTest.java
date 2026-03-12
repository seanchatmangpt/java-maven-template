package io.github.seanchatmangpt.jotp.messaging.routing;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("ComposedMessageProcessor Tests")
class ComposedMessageProcessorTest {

  @Test
  @DisplayName("should compose multiple routers sequentially")
  void testComposedRouters() {
    // Arrange
    var processor =
        ComposedMessageProcessor.compose(
            msg -> msg.trim(),
            msg -> msg.toUpperCase(),
            msg -> msg + "!");

    // Act
    String result = processor.apply("  hello world  ");

    // Assert
    assertThat(result).isEqualTo("HELLO WORLD!");
  }

  @Test
  @DisplayName("should apply filters and reject non-matching messages")
  void testFiltering() {
    // Arrange
    var processor =
        ComposedMessageProcessor.compose(msg -> msg.trim())
            .thenFilter(msg -> !msg.isEmpty())
            .thenFilter(msg -> msg.length() > 2);

    // Act & Assert
    assertThat(processor.apply("hi")).isNull(); // Too short
    assertThat(processor.apply("   ")).isNull(); // Empty after trim
    assertThat(processor.apply("hello")).isEqualTo("hello"); // Passes all filters
  }

  @Test
  @DisplayName("should support null messages in apply")
  void testNullHandling() {
    // Arrange
    var processor = ComposedMessageProcessor.compose(String::toUpperCase);

    // Act & Assert
    assertThat(processor.apply(null)).isNull();
  }

  @Test
  @DisplayName("should chain multiple processors with andThen")
  void testChaining() {
    // Arrange
    var first =
        ComposedMessageProcessor.compose(msg -> msg.trim())
            .thenFilter(msg -> !msg.isEmpty());

    var second = ComposedMessageProcessor.compose(msg -> msg.toUpperCase());

    var combined = first.andThen(second);

    // Act
    String result = combined.apply("  hello  ");

    // Assert
    assertThat(result).isEqualTo("HELLO");
  }

  @Test
  @DisplayName("should support side-effect observations with peek")
  void testPeek() {
    // Arrange
    List<String> observed = new ArrayList<>();
    var processor =
        ComposedMessageProcessor.compose(msg -> msg.toUpperCase())
            .peek(observed::add)
            .thenRoute(msg -> msg + "!");

    // Act
    String result = processor.apply("hello");

    // Assert
    assertThat(result).isEqualTo("HELLO!");
    assertThat(observed).containsExactly("HELLO");
  }

  @Test
  @DisplayName("should support Optional wrapper with applyOptional")
  void testApplyOptional() {
    // Arrange
    var processor =
        ComposedMessageProcessor.compose(msg -> msg.trim())
            .thenFilter(msg -> msg.length() > 3);

    // Act
    Optional<String> result1 = processor.applyOptional("hello");
    Optional<String> result2 = processor.applyOptional("hi");

    // Assert
    assertThat(result1).contains("hello");
    assertThat(result2).isEmpty();
  }

  @Test
  @DisplayName("should execute routers before filters")
  void testRouterBeforeFilter() {
    // Arrange
    var processor =
        ComposedMessageProcessor.compose(msg -> msg.trim(), msg -> msg.toUpperCase())
            .thenFilter(msg -> msg.startsWith("HELLO"));

    // Act
    String result = processor.apply("  hello world  ");

    // Assert
    assertThat(result).isEqualTo("HELLO WORLD");
  }

  @Test
  @DisplayName("should short-circuit on first failing filter")
  void testShortCircuitOnFilter() {
    // Arrange
    List<String> trace = new ArrayList<>();

    var processor =
        ComposedMessageProcessor.compose(
                msg -> {
                  trace.add("router");
                  return msg.toUpperCase();
                })
            .thenFilter(
                msg -> {
                  trace.add("filter1");
                  return msg.contains("X");
                })
            .thenFilter(
                msg -> {
                  trace.add("filter2");
                  return true;
                });

    // Act
    String result = processor.apply("hello");

    // Assert
    assertThat(result).isNull();
    assertThat(trace).containsExactly("router", "filter1");
    // filter2 never executes because filter1 rejected
  }

  @Test
  @DisplayName("should handle compose with null routers (skip them)")
  void testComposeWithNullRouters() {
    // Arrange
    var processor =
        ComposedMessageProcessor.compose(
            msg -> msg.trim(),
            null,
            msg -> msg.toUpperCase());

    // Act
    String result = processor.apply("  hello  ");

    // Assert
    assertThat(result).isEqualTo("HELLO");
  }

  @Test
  @DisplayName("should transform complex objects through pipeline")
  void testComplexObjectTransformation() {
    // Arrange
    record Order(String item, int quantity) {}

    var processor =
        ComposedMessageProcessor.<Order>compose(
                order -> new Order(order.item().toUpperCase(), order.quantity()))
            .thenFilter(order -> order.quantity > 0)
            .thenRoute(order -> new Order(order.item + " [Q:" + order.quantity + "]",
                order.quantity));

    // Act
    Order input = new Order("apple", 5);
    Order result = processor.apply(input);

    // Assert
    assertThat(result).isEqualTo(new Order("APPLE [Q:5]", 5));
  }

  @Test
  @DisplayName("should maintain message integrity through multi-stage pipeline")
  void testMultiStagePipeline() {
    // Arrange
    var processor =
        ComposedMessageProcessor.compose(
                msg -> msg.replaceAll("\\s+", " "), // Normalize spaces
                msg -> msg.trim() // Trim ends
                )
            .thenFilter(msg -> msg.length() >= 5) // Min length
            .thenFilter(msg -> !msg.startsWith("ERROR")) // Reject errors
            .thenRoute(msg -> "[PROCESSED] " + msg);

    // Act & Assert
    assertThat(processor.apply("  hello   world  ")).isEqualTo("[PROCESSED] hello world");
    assertThat(processor.apply("hi")).isNull(); // Too short
    assertThat(processor.apply("ERROR: bad")).isNull(); // Starts with ERROR
  }

  @Test
  @DisplayName("should compose message routers for declarative DSL")
  void testDeclarativeDSL() {
    // Arrange: Build a message routing DSL
    var processor =
        ComposedMessageProcessor.compose(
                msg -> msg.toLowerCase(), // Normalize case
                msg -> msg.replaceAll("[^a-z0-9 ]", "")) // Remove special chars
            .thenFilter(msg -> !msg.isBlank()) // Reject blank
            .thenRoute(msg -> "[ROUTED] " + msg);

    // Act
    String result = processor.apply("Hello-World_123!");

    // Assert
    assertThat(result).isEqualTo("[ROUTED] helloworld123");
  }

  @Test
  @DisplayName("should allow thenRoute null (skip it)")
  void testThenRouteNull() {
    // Arrange
    var processor =
        ComposedMessageProcessor.compose(msg -> msg.toUpperCase())
            .thenRoute(null) // Skip
            .thenRoute(msg -> msg + "!");

    // Act
    String result = processor.apply("hello");

    // Assert
    assertThat(result).isEqualTo("HELLO!");
  }

  @Test
  @DisplayName("should support stateful side effects via peek")
  void testStatefulPeek() {
    // Arrange
    List<String> log = new ArrayList<>();

    var processor =
        ComposedMessageProcessor.compose(msg -> msg.toUpperCase())
            .peek(msg -> log.add("Step 1: " + msg))
            .thenRoute(msg -> msg + "!")
            .peek(msg -> log.add("Step 2: " + msg));

    // Act
    processor.apply("hello");

    // Assert
    assertThat(log).containsExactly("Step 1: HELLO", "Step 2: HELLO!");
  }
}
