package io.github.seanchatmangpt.jotp.messaging.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import org.assertj.core.api.WithAssertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Tests for {@link ContentBasedRouter} — Vernon's Content-Based Router pattern implemented with JOTP.
 *
 * <p>Covers routing correctness, edge cases, and performance characteristics.
 */
@Timeout(10)
@DisplayName("Content-Based Router Tests")
class ContentBasedRouterTest implements WithAssertions {

    record Message(String type, int priority, String content) {}

    @Nested
    @DisplayName("Basic Routing")
    class BasicRouting {

        @Test
        void routesToFirstMatchingPredicate() throws InterruptedException {
            AtomicInteger urgentCount = new AtomicInteger(0);
            AtomicInteger normalCount = new AtomicInteger(0);
            CountDownLatch latch = new CountDownLatch(2);

            ContentBasedRouter<Message> router = new ContentBasedRouter<>();
            router.addRoute(msg -> msg.priority() >= 8, msg -> {
                urgentCount.incrementAndGet();
                latch.countDown();
            });
            router.addRoute(msg -> msg.priority() < 8, msg -> {
                normalCount.incrementAndGet();
                latch.countDown();
            });

            router.route(new Message("alert", 9, "urgent"));
            router.route(new Message("info", 3, "normal"));

            latch.await();

            assertThat(urgentCount.get()).isEqualTo(1);
            assertThat(normalCount.get()).isEqualTo(1);

            router.process().stop();
        }

        @Test
        void firstMatchingRouteWins() throws InterruptedException {
            AtomicInteger firstRoute = new AtomicInteger(0);
            AtomicInteger secondRoute = new AtomicInteger(0);
            CountDownLatch latch = new CountDownLatch(1);

            ContentBasedRouter<Message> router = new ContentBasedRouter<>();
            // Both predicates match, but first should be used
            router.addRoute(msg -> msg.priority() >= 5, msg -> {
                firstRoute.incrementAndGet();
                latch.countDown();
            });
            router.addRoute(msg -> msg.priority() >= 3, msg -> {
                secondRoute.incrementAndGet();
                latch.countDown();
            });

            router.route(new Message("order", 7, "content"));

            latch.await();

            assertThat(firstRoute.get()).isEqualTo(1);
            assertThat(secondRoute.get()).isEqualTo(0); // Should not be invoked
            router.process().stop();
        }

        @Test
        void unmatchedMessagesSilentlyDropped() throws InterruptedException {
            AtomicInteger delivered = new AtomicInteger(0);
            Thread.sleep(100); // Let any spurious messages settle

            ContentBasedRouter<Message> router = new ContentBasedRouter<>();
            router.addRoute(msg -> msg.type().equals("ORDER"), msg -> delivered.incrementAndGet());

            // Send a message that doesn't match
            router.route(new Message("UNKNOWN", 5, "content"));

            // Wait a bit to ensure no delivery
            await().atMost(Duration.ofMillis(500)).until(() -> true);

            assertThat(delivered.get()).isEqualTo(0);
            router.process().stop();
        }
    }

    @Nested
    @DisplayName("Default Route")
    class DefaultRoute {

        @Test
        void defaultRouteHandlesUnmatchedMessages() throws InterruptedException {
            AtomicInteger mainRoute = new AtomicInteger(0);
            AtomicInteger defaultRoute = new AtomicInteger(0);
            CountDownLatch latch = new CountDownLatch(2);

            ContentBasedRouter<Message> router = new ContentBasedRouter<>();
            router.addRoute(msg -> msg.type().equals("ORDER"), msg -> {
                mainRoute.incrementAndGet();
                latch.countDown();
            });
            router.setDefault(msg -> {
                defaultRoute.incrementAndGet();
                latch.countDown();
            });

            router.route(new Message("ORDER", 5, "matching"));
            router.route(new Message("UNKNOWN", 5, "unmatched"));

            latch.await();

            assertThat(mainRoute.get()).isEqualTo(1);
            assertThat(defaultRoute.get()).isEqualTo(1);
            router.process().stop();
        }

        @Test
        void nullDefaultDropsUnmatched() throws InterruptedException {
            AtomicInteger delivered = new AtomicInteger(0);
            CountDownLatch latch = new CountDownLatch(1);

            ContentBasedRouter<Message> router = new ContentBasedRouter<>();
            router.addRoute(msg -> msg.type().equals("ORDER"), msg -> {
                delivered.incrementAndGet();
                latch.countDown();
            });
            router.setDefault(null); // Explicitly null default

            router.route(new Message("ORDER", 5, "match"));
            latch.await();

            router.route(new Message("UNKNOWN", 5, "drop"));

            // Wait to ensure no additional delivery
            await().atMost(Duration.ofMillis(500)).until(() -> true);

            assertThat(delivered.get()).isEqualTo(1);
            router.process().stop();
        }
    }

    @Nested
    @DisplayName("Edge Cases")
    class EdgeCases {

        @Test
        void nullPredicateThrows() {
            ContentBasedRouter<Message> router = new ContentBasedRouter<>();

            assertThatThrownBy(() -> router.addRoute(null, msg -> {}))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("predicate");
        }

        @Test
        void nullDestinationThrows() {
            ContentBasedRouter<Message> router = new ContentBasedRouter<>();

            assertThatThrownBy(() -> router.addRoute(msg -> true, null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("destination");
        }

        @Test
        void nullMessageThrows() {
            ContentBasedRouter<Message> router = new ContentBasedRouter<>();
            router.addRoute(msg -> true, msg -> {});

            assertThatThrownBy(() -> router.route(null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("message");
        }

        @Test
        void emptyRouterDropsAll() throws InterruptedException {
            AtomicInteger deliveries = new AtomicInteger(0);

            ContentBasedRouter<Message> router = new ContentBasedRouter<>();
            // No routes added

            router.route(new Message("ANY", 5, "should drop"));
            router.route(new Message("ANY", 5, "should drop"));

            // Wait to ensure nothing is delivered
            await().atMost(Duration.ofMillis(500)).until(() -> true);

            assertThat(deliveries.get()).isEqualTo(0);
            router.process().stop();
        }
    }

    @Nested
    @DisplayName("Concurrency")
    class Concurrency {

        @Test
        void concurrentRouting() throws InterruptedException {
            AtomicInteger routedCount = new AtomicInteger(0);
            int messageCount = 100;
            CountDownLatch latch = new CountDownLatch(messageCount);

            ContentBasedRouter<Message> router = new ContentBasedRouter<>();
            router.addRoute(msg -> msg.priority() >= 5, msg -> {
                routedCount.incrementAndGet();
                latch.countDown();
            });

            // Send messages from multiple virtual threads
            for (int i = 0; i < messageCount; i++) {
                final int priority = i % 10;
                Thread.ofVirtual().start(() -> {
                    if (priority >= 5) {
                        router.route(new Message("msg", priority, "content"));
                    }
                });
            }

            latch.await();

            // Expect messages with priority 5,6,7,8,9 (5 out of 10 = 50)
            assertThat(routedCount.get()).isEqualTo(50);
            router.process().stop();
        }

        @Test
        void addRoutesDuringRouting() throws InterruptedException {
            AtomicInteger route1 = new AtomicInteger(0);
            AtomicInteger route2 = new AtomicInteger(0);
            CountDownLatch latch = new CountDownLatch(2);

            ContentBasedRouter<Message> router = new ContentBasedRouter<>();
            router.addRoute(msg -> msg.type().equals("TYPE1"), msg -> {
                route1.incrementAndGet();
                latch.countDown();
            });

            router.route(new Message("TYPE1", 5, "first"));

            // Add second route while first is being used
            router.addRoute(msg -> msg.type().equals("TYPE2"), msg -> {
                route2.incrementAndGet();
                latch.countDown();
            });

            router.route(new Message("TYPE2", 5, "second"));

            latch.await();

            assertThat(route1.get()).isEqualTo(1);
            assertThat(route2.get()).isEqualTo(1);
            router.process().stop();
        }
    }

    @Nested
    @DisplayName("Performance")
    class Performance {

        @Test
        void highThroughputRouting() throws InterruptedException {
            ConcurrentHashMap<String, AtomicInteger> destinations = new ConcurrentHashMap<>();
            destinations.put("A", new AtomicInteger(0));
            destinations.put("B", new AtomicInteger(0));
            destinations.put("C", new AtomicInteger(0));

            int messageCount = 1000;
            CountDownLatch latch = new CountDownLatch(messageCount);

            ContentBasedRouter<Message> router = new ContentBasedRouter<>();
            router.addRoute(msg -> msg.priority() <= 3, msg -> {
                destinations.get("A").incrementAndGet();
                latch.countDown();
            });
            router.addRoute(msg -> msg.priority() > 3 && msg.priority() <= 6, msg -> {
                destinations.get("B").incrementAndGet();
                latch.countDown();
            });
            router.addRoute(msg -> msg.priority() > 6, msg -> {
                destinations.get("C").incrementAndGet();
                latch.countDown();
            });

            long start = System.currentTimeMillis();

            for (int i = 0; i < messageCount; i++) {
                router.route(new Message("msg", i % 10, "content"));
            }

            latch.await();

            long elapsed = System.currentTimeMillis() - start;

            assertThat(destinations.get("A").get()).isGreaterThan(0);
            assertThat(destinations.get("B").get()).isGreaterThan(0);
            assertThat(destinations.get("C").get()).isGreaterThan(0);
            assertThat(elapsed).isLessThan(5000); // Should complete quickly

            router.process().stop();
        }
    }
}
