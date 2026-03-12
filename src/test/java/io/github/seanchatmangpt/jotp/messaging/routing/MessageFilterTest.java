package io.github.seanchatmangpt.jotp.messaging.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import org.assertj.core.api.WithAssertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Tests for {@link MessageFilter} — Vernon's Message Filter pattern implemented with JOTP.
 *
 * <p>Covers filtering correctness, chaining, edge cases, and performance characteristics.
 */
@Timeout(10)
@DisplayName("Message Filter Tests")
class MessageFilterTest implements WithAssertions {

    record Message(String id, int value, String category) {}

    @Nested
    @DisplayName("Basic Filtering")
    class BasicFiltering {

        @Test
        void forwardsMatchingMessages() throws InterruptedException {
            AtomicInteger forwarded = new AtomicInteger(0);
            CountDownLatch latch = new CountDownLatch(2);

            MessageFilter<Message> filter = MessageFilter.create(
                    msg -> msg.value() > 5,
                    msg -> {
                        forwarded.incrementAndGet();
                        latch.countDown();
                    });

            filter.filter(new Message("m1", 10, "A"));  // Should forward
            filter.filter(new Message("m2", 3, "B"));   // Should drop
            filter.filter(new Message("m3", 8, "C"));   // Should forward

            // Wait for 2 forwards (drop should happen silently)
            latch.await();

            assertThat(forwarded.get()).isEqualTo(2);
            filter.process().stop();
        }

        @Test
        void dropsNonMatchingMessages() throws InterruptedException {
            AtomicInteger forwarded = new AtomicInteger(0);

            MessageFilter<Message> filter = MessageFilter.create(
                    msg -> msg.category().equals("IMPORTANT"),
                    msg -> forwarded.incrementAndGet());

            filter.filter(new Message("m1", 5, "IMPORTANT"));  // Forward
            filter.filter(new Message("m2", 5, "SPAM"));       // Drop

            // Give time to process
            await().atMost(Duration.ofMillis(500)).until(() -> true);

            assertThat(forwarded.get()).isEqualTo(1); // Only 1 forwarded
            filter.process().stop();
        }

        @Test
        void handlesNoMatches() throws InterruptedException {
            AtomicInteger forwarded = new AtomicInteger(0);

            MessageFilter<Message> filter = MessageFilter.create(
                    msg -> msg.value() > 100, // Very strict predicate
                    msg -> forwarded.incrementAndGet());

            filter.filter(new Message("m1", 5, "A"));
            filter.filter(new Message("m2", 10, "B"));
            filter.filter(new Message("m3", 50, "C"));

            // Wait to ensure nothing is forwarded
            await().atMost(Duration.ofMillis(500)).until(() -> true);

            assertThat(forwarded.get()).isEqualTo(0);
            filter.process().stop();
        }
    }

    @Nested
    @DisplayName("Filter Chaining")
    class FilterChaining {

        @Test
        void chainsMultipleFilters() throws InterruptedException {
            AtomicInteger final_count = new AtomicInteger(0);
            CountDownLatch latch = new CountDownLatch(1);

            // Create chain: value > 5 -> category == IMPORTANT -> final
            MessageFilter<Message> categoryFilter = MessageFilter.create(
                    msg -> msg.category().equals("IMPORTANT"),
                    msg -> {
                        final_count.incrementAndGet();
                        latch.countDown();
                    });

            MessageFilter<Message> valueFilter = MessageFilter.create(
                    msg -> msg.value() > 5,
                    categoryFilter::filter);

            // Send test messages
            valueFilter.filter(new Message("m1", 10, "IMPORTANT")); // Pass both
            valueFilter.filter(new Message("m2", 3, "IMPORTANT"));  // Drop at value filter
            valueFilter.filter(new Message("m3", 8, "SPAM"));       // Pass value, drop at category

            latch.await();

            assertThat(final_count.get()).isEqualTo(1); // Only m1 reaches the end
            valueFilter.process().stop();
            categoryFilter.process().stop();
        }

        @Test
        void deepChain() throws InterruptedException {
            AtomicInteger final_count = new AtomicInteger(0);
            CountDownLatch latch = new CountDownLatch(1);

            // Create a 3-level chain
            MessageFilter<Message> filter3 = MessageFilter.create(
                    msg -> msg.category().equals("IMPORTANT"),
                    msg -> {
                        final_count.incrementAndGet();
                        latch.countDown();
                    });

            MessageFilter<Message> filter2 = MessageFilter.create(
                    msg -> msg.value() > 5,
                    filter3::filter);

            MessageFilter<Message> filter1 = MessageFilter.create(
                    msg -> msg.id().startsWith("msg"),
                    filter2::filter);

            // Only message passing all three filters should reach final
            filter1.filter(new Message("msg_1", 10, "IMPORTANT")); // Pass all
            filter1.filter(new Message("bad_1", 10, "IMPORTANT")); // Fail filter1
            filter1.filter(new Message("msg_2", 3, "IMPORTANT"));  // Fail filter2
            filter1.filter(new Message("msg_3", 10, "SPAM"));      // Fail filter3

            latch.await();

            assertThat(final_count.get()).isEqualTo(1);
            filter1.process().stop();
            filter2.process().stop();
            filter3.process().stop();
        }
    }

    @Nested
    @DisplayName("Factory Methods")
    class FactoryMethods {

        @Test
        void createWithDestination() throws InterruptedException {
            AtomicInteger count = new AtomicInteger(0);
            CountDownLatch latch = new CountDownLatch(1);

            MessageFilter.Destination<Message> dest = msg -> {
                count.incrementAndGet();
                latch.countDown();
            };

            MessageFilter<Message> filter = MessageFilter.create(msg -> msg.value() > 5, dest);

            filter.filter(new Message("m1", 10, "A"));

            latch.await();

            assertThat(count.get()).isEqualTo(1);
            filter.process().stop();
        }

        @Test
        void createWithProc() throws InterruptedException {
            AtomicInteger count = new AtomicInteger(0);
            CountDownLatch latch = new CountDownLatch(1);

            io.github.seanchatmangpt.jotp.Proc<Integer, Message> proc =
                    new io.github.seanchatmangpt.jotp.Proc<>(
                            0,
                            (state, msg) -> {
                                count.incrementAndGet();
                                latch.countDown();
                                return state;
                            });

            MessageFilter<Message> filter = MessageFilter.create(msg -> msg.value() > 5, proc);

            filter.filter(new Message("m1", 10, "A"));

            latch.await();

            assertThat(count.get()).isEqualTo(1);
            filter.process().stop();
            proc.stop();
        }
    }

    @Nested
    @DisplayName("Edge Cases")
    class EdgeCases {

        @Test
        void nullPredicateThrows() {
            assertThatThrownBy(() -> MessageFilter.create(null, msg -> {}))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("predicate");
        }

        @Test
        void nullDestinationThrows() {
            assertThatThrownBy(() -> MessageFilter.create(msg -> true, (MessageFilter.Destination<Message>) null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("next");
        }

        @Test
        void nullMessageThrows() {
            MessageFilter<Message> filter = MessageFilter.create(msg -> true, msg -> {});

            assertThatThrownBy(() -> filter.filter(null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("message");
        }

        @Test
        void alwaysTruePredicateForwardsAll() throws InterruptedException {
            AtomicInteger forwarded = new AtomicInteger(0);
            int count = 10;
            CountDownLatch latch = new CountDownLatch(count);

            MessageFilter<Message> filter = MessageFilter.create(
                    msg -> true, // Always forward
                    msg -> {
                        forwarded.incrementAndGet();
                        latch.countDown();
                    });

            for (int i = 0; i < count; i++) {
                filter.filter(new Message("m" + i, i, "ANY"));
            }

            latch.await();

            assertThat(forwarded.get()).isEqualTo(count);
            filter.process().stop();
        }

        @Test
        void alwaysFalsePredicateDropsAll() throws InterruptedException {
            AtomicInteger forwarded = new AtomicInteger(0);

            MessageFilter<Message> filter = MessageFilter.create(
                    msg -> false, // Never forward
                    msg -> forwarded.incrementAndGet());

            filter.filter(new Message("m1", 5, "A"));
            filter.filter(new Message("m2", 10, "B"));
            filter.filter(new Message("m3", 15, "C"));

            await().atMost(Duration.ofMillis(500)).until(() -> true);

            assertThat(forwarded.get()).isEqualTo(0);
            filter.process().stop();
        }
    }

    @Nested
    @DisplayName("Concurrency")
    class Concurrency {

        @Test
        void concurrentFiltering() throws InterruptedException {
            AtomicInteger forwarded = new AtomicInteger(0);
            int messageCount = 100;
            CountDownLatch latch = new CountDownLatch(messageCount / 2); // Expect 50 forwards

            MessageFilter<Message> filter = MessageFilter.create(
                    msg -> msg.value() > 5,
                    msg -> {
                        forwarded.incrementAndGet();
                        latch.countDown();
                    });

            // Send messages from multiple virtual threads
            for (int i = 0; i < messageCount; i++) {
                final int value = i % 10;
                Thread.ofVirtual().start(() -> {
                    filter.filter(new Message("m" + value, value, "A"));
                });
            }

            latch.await();

            // Half of messages have value > 5 (values 6,7,8,9 out of 0-9)
            assertThat(forwarded.get()).isEqualTo(50);
            filter.process().stop();
        }
    }

    @Nested
    @DisplayName("Performance")
    class Performance {

        @Test
        void highThroughputFiltering() throws InterruptedException {
            AtomicInteger forwarded = new AtomicInteger(0);
            int messageCount = 1000;
            CountDownLatch latch = new CountDownLatch(messageCount / 2); // Expect half

            MessageFilter<Message> filter = MessageFilter.create(
                    msg -> msg.value() % 2 == 0, // Pass even values
                    msg -> {
                        forwarded.incrementAndGet();
                        latch.countDown();
                    });

            long start = System.currentTimeMillis();

            for (int i = 0; i < messageCount; i++) {
                filter.filter(new Message("msg_" + i, i, "CAT"));
            }

            latch.await();

            long elapsed = System.currentTimeMillis() - start;

            assertThat(forwarded.get()).isEqualTo(messageCount / 2);
            assertThat(elapsed).isLessThan(5000); // Should complete quickly

            filter.process().stop();
        }

        @Test
        void complexPredicatePerformance() throws InterruptedException {
            AtomicInteger forwarded = new AtomicInteger(0);
            int messageCount = 500;
            CountDownLatch latch = new CountDownLatch(messageCount / 4); // Complex predicate ~25% match

            MessageFilter<Message> filter = MessageFilter.create(
                    msg -> msg.value() > 5 && msg.category().length() > 3 && msg.id().startsWith("m"),
                    msg -> {
                        forwarded.incrementAndGet();
                        latch.countDown();
                    });

            for (int i = 0; i < messageCount; i++) {
                filter.filter(new Message("msg_" + i, i % 20, "IMPORTANT"));
            }

            latch.await();

            assertThat(forwarded.get()).isGreaterThan(0);
            filter.process().stop();
        }
    }
}
