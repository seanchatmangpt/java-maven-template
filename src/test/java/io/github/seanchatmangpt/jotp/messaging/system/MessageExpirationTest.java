package io.github.seanchatmangpt.jotp.messaging.system;

import static java.time.Instant.*;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import org.assertj.core.api.WithAssertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Verifies Message Expiration pattern for TTL-based message filtering and cleanup.
 *
 * <p>Key expiration invariants under test:
 *
 * <ol>
 *   <li>withExpiration wraps messages with TTL metadata
 *   <li>isExpired checks against current time
 *   <li>isExpiredAt checks against arbitrary instant
 *   <li>cleanupExpired filters streams of expiring messages
 *   <li>retainNonExpired filters lists in-place
 *   <li>extractExpired removes and returns expired messages
 *   <li>timeUntilExpiration calculates remaining TTL
 *   <li>ageMs calculates elapsed time
 *   <li>expiresAt computes exact expiration instant
 * </ol>
 */
@Timeout(10)
class MessageExpirationTest implements WithAssertions {

    // Test message type
    sealed interface NotificationMsg permits NotificationMsg.Alert {
        record Alert(String alertId, String severity) implements NotificationMsg {
            Alert {
                if (alertId == null || alertId.isBlank()) {
                    throw new IllegalArgumentException("alertId required");
                }
            }
        }
    }

    // -------------------------------------------------------
    // 1. Basic withExpiration wrapping
    // -------------------------------------------------------

    @Test
    void withExpiration_wrapsMessageWithTTL() {
        var expiration = MessageExpiration.create(5000);
        var msg = new NotificationMsg.Alert("ALERT-001", "HIGH");

        var expiring = expiration.withExpiration(msg, 2000);

        assertThat(expiring.message()).isEqualTo(msg);
        assertThat(expiring.ttlMs()).isEqualTo(2000);
        assertThat(expiring.createdAt()).isNotNull();
    }

    // -------------------------------------------------------
    // 2. TTL validation
    // -------------------------------------------------------

    @Test
    void withExpiration_rejectsNonPositiveTTL() {
        var expiration = MessageExpiration.create(5000);
        var msg = new NotificationMsg.Alert("ALERT-001", "HIGH");

        assertThatThrownBy(() -> expiration.withExpiration(msg, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("positive");

        assertThatThrownBy(() -> expiration.withExpiration(msg, -100))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("positive");
    }

    // -------------------------------------------------------
    // 3. isExpired checks
    // -------------------------------------------------------

    @Test
    void isExpired_returnsFalseBeforeTTL() throws InterruptedException {
        var expiration = MessageExpiration.create(5000);
        var msg = new NotificationMsg.Alert("ALERT-001", "HIGH");
        var expiring = expiration.withExpiration(msg, 1000);

        // Should not be expired immediately
        assertThat(expiration.isExpired(expiring)).isFalse();

        // Still valid after 500ms
        Thread.sleep(500);
        assertThat(expiration.isExpired(expiring)).isFalse();
    }

    @Test
    void isExpired_returnsTrueAfterTTL() throws InterruptedException {
        var expiration = MessageExpiration.create(5000);
        var msg = new NotificationMsg.Alert("ALERT-001", "HIGH");
        var expiring = expiration.withExpiration(msg, 500);

        assertThat(expiration.isExpired(expiring)).isFalse();

        Thread.sleep(600);
        assertThat(expiration.isExpired(expiring)).isTrue();
    }

    // -------------------------------------------------------
    // 4. isExpiredAt instant-specific checks
    // -------------------------------------------------------

    @Test
    void isExpiredAt_checksAgainstSpecificInstant() {
        var expiration = MessageExpiration.create(5000);
        var msg = new NotificationMsg.Alert("ALERT-001", "HIGH");
        var created = Instant.now();
        var expiring =
                new MessageExpiration.ExpiringMessage<>(msg, 1000, created);

        var beforeExpiry = created.plusMillis(500);
        var atExpiry = created.plusMillis(1000);
        var afterExpiry = created.plusMillis(1500);

        assertThat(expiration.isExpiredAt(expiring, beforeExpiry)).isFalse();
        assertThat(expiration.isExpiredAt(expiring, atExpiry)).isTrue();
        assertThat(expiration.isExpiredAt(expiring, afterExpiry)).isTrue();
    }

    // -------------------------------------------------------
    // 5. expiresAt calculation
    // -------------------------------------------------------

    @Test
    void expiresAt_calculatesCorrectInstant() {
        var expiration = MessageExpiration.create(5000);
        var msg = new NotificationMsg.Alert("ALERT-001", "HIGH");
        var before = Instant.now();

        var expiring = expiration.withExpiration(msg, 2000);
        var expiresAt = expiring.expiresAt();

        var after = Instant.now();

        // expiresAt should be roughly 2000ms in the future from when message was created
        var lowerBound = before.plusMillis(2000);
        var upperBound = after.plusMillis(2000).plusMillis(100); // Allow 100ms skew

        assertThat(expiresAt).isGreaterThanOrEqualTo(lowerBound).isLessThanOrEqualTo(upperBound);
    }

    // -------------------------------------------------------
    // 6. timeUntilExpiration countdown
    // -------------------------------------------------------

    @Test
    void timeUntilExpiration_calculatesRemainingTime() throws InterruptedException {
        var expiration = MessageExpiration.create(5000);
        var msg = new NotificationMsg.Alert("ALERT-001", "HIGH");
        var expiring = expiration.withExpiration(msg, 1000);

        var remaining1 = expiration.timeUntilExpiration(expiring);
        assertThat(remaining1).isPositive().isLessThanOrEqualTo(1000);

        Thread.sleep(600);

        var remaining2 = expiration.timeUntilExpiration(expiring);
        assertThat(remaining2).isLessThan(remaining1).isPositive();

        Thread.sleep(600);

        var remaining3 = expiration.timeUntilExpiration(expiring);
        assertThat(remaining3).isNegative(); // Should be expired
    }

    // -------------------------------------------------------
    // 7. ageMs elapsed time calculation
    // -------------------------------------------------------

    @Test
    void ageMs_calculatesElapsedTime() throws InterruptedException {
        var expiration = MessageExpiration.create(5000);
        var msg = new NotificationMsg.Alert("ALERT-001", "HIGH");
        var expiring = expiration.withExpiration(msg, 5000);

        var age1 = expiration.ageMs(expiring);
        assertThat(age1).isGreaterThanOrEqualTo(0).isLessThan(50);

        Thread.sleep(200);

        var age2 = expiration.ageMs(expiring);
        assertThat(age2).isGreaterThanOrEqualTo(200);
    }

    // -------------------------------------------------------
    // 8. cleanupExpired stream filtering
    // -------------------------------------------------------

    @Test
    void cleanupExpired_filtersStreamOfMessages() throws InterruptedException {
        var expiration = MessageExpiration.create(5000);

        var msgs = new ArrayList<MessageExpiration.ExpiringMessage<NotificationMsg>>();
        for (int i = 1; i <= 3; i++) {
            var msg = new NotificationMsg.Alert("ALERT-" + i, "HIGH");
            msgs.add(expiration.withExpiration(msg, 1000));
        }

        // All valid before TTL
        var valid1 = msgs.stream().collect(Collectors.toList());
        assertThat(valid1).hasSize(3);

        // Wait for expiration
        Thread.sleep(1100);

        // Filter using cleanupExpired
        var cleaned = expiration.cleanupExpired(msgs.stream()).collect(Collectors.toList());
        assertThat(cleaned).isEmpty();
    }

    // -------------------------------------------------------
    // 9. retainNonExpired list filtering
    // -------------------------------------------------------

    @Test
    void retainNonExpired_filtersListInPlace() throws InterruptedException {
        var expiration = MessageExpiration.create(5000);

        var msgs = new ArrayList<MessageExpiration.ExpiringMessage<NotificationMsg>>();
        for (int i = 1; i <= 5; i++) {
            var msg = new NotificationMsg.Alert("ALERT-" + i, "HIGH");
            long ttl = (i <= 2) ? 500 : 2000; // First 2 expire quickly
            msgs.add(expiration.withExpiration(msg, ttl));
        }

        // All valid initially
        assertThat(msgs).hasSize(5);

        Thread.sleep(600);

        var retained = expiration.retainNonExpired(msgs);

        // Should keep the 3 with longer TTL
        assertThat(retained).hasSize(3);
        assertThat(retained).allMatch(m -> !expiration.isExpired(m));
    }

    // -------------------------------------------------------
    // 10. extractExpired separation
    // -------------------------------------------------------

    @Test
    void extractExpired_removesAndReturnsExpired() throws InterruptedException {
        var expiration = MessageExpiration.create(5000);

        var msgs = new ArrayList<MessageExpiration.ExpiringMessage<NotificationMsg>>();
        for (int i = 1; i <= 6; i++) {
            var msg = new NotificationMsg.Alert("ALERT-" + i, "HIGH");
            long ttl = (i <= 3) ? 400 : 2000;
            msgs.add(expiration.withExpiration(msg, ttl));
        }

        Thread.sleep(500);

        var expired = expiration.extractExpired(msgs);

        // Extracted expired
        assertThat(expired).hasSize(3);
        assertThat(expired).allMatch(m -> expiration.isExpired(m));

        // Remaining are valid
        assertThat(msgs).hasSize(3);
        assertThat(msgs).allMatch(m -> !expiration.isExpired(m));
    }

    // -------------------------------------------------------
    // 11. Expiration manager creation validation
    // -------------------------------------------------------

    @Test
    void create_rejectsNonPositiveCleanupInterval() {
        assertThatThrownBy(() -> MessageExpiration.create(0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("positive");

        assertThatThrownBy(() -> MessageExpiration.create(-5000))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("positive");
    }

    // -------------------------------------------------------
    // 12. cleanupIntervalMs accessor
    // -------------------------------------------------------

    @Test
    void cleanupIntervalMs_returnsConfiguredValue() {
        var expiration1 = MessageExpiration.create(5000);
        assertThat(expiration1.cleanupIntervalMs()).isEqualTo(5000);

        var expiration2 = MessageExpiration.create(10000);
        assertThat(expiration2.cleanupIntervalMs()).isEqualTo(10000);
    }

    // -------------------------------------------------------
    // 13. wrapMessage JOTP Message integration
    // -------------------------------------------------------

    @Test
    void wrapMessage_wrapsJOTPMessage() {
        var expiration = MessageExpiration.create(5000);
        var jotpMsg =
                io.github.seanchatmangpt.jotp.messaging.Message.event(
                        "OrderCreated", new Object());

        var wrapped = expiration.wrapMessage(jotpMsg, 3000);

        assertThat(wrapped.message()).isEqualTo(jotpMsg);
        assertThat(wrapped.ttlMs()).isEqualTo(3000);
        assertThat(expiration.isExpired(wrapped)).isFalse();
    }

    // -------------------------------------------------------
    // 14. Batch cleanup with mixed expiration times
    // -------------------------------------------------------

    @Test
    void batchCleanup_handlesMixedExpirationTimes() throws InterruptedException {
        var expiration = MessageExpiration.create(5000);

        var msgs = new ArrayList<MessageExpiration.ExpiringMessage<NotificationMsg>>();
        msgs.add(expiration.withExpiration(new NotificationMsg.Alert("ALERT-1", "HIGH"), 300));
        msgs.add(expiration.withExpiration(new NotificationMsg.Alert("ALERT-2", "MEDIUM"), 600));
        msgs.add(expiration.withExpiration(new NotificationMsg.Alert("ALERT-3", "LOW"), 900));
        msgs.add(expiration.withExpiration(new NotificationMsg.Alert("ALERT-4", "HIGH"), 1200));

        // Check after 500ms: first has expired
        Thread.sleep(500);
        var after500 = expiration.retainNonExpired(new ArrayList<>(msgs));
        assertThat(after500).hasSize(3);

        // Check after 700ms total: first two have expired
        Thread.sleep(200);
        var after700 = expiration.retainNonExpired(new ArrayList<>(msgs));
        assertThat(after700).hasSize(2);

        // Check after 1000ms total: first three have expired
        Thread.sleep(300);
        var after1000 = expiration.retainNonExpired(new ArrayList<>(msgs));
        assertThat(after1000).hasSize(1);
    }

    // -------------------------------------------------------
    // 15. Empty collection handling
    // -------------------------------------------------------

    @Test
    void emptyCollections_handledSafely() {
        var expiration = MessageExpiration.create(5000);

        var empty = new ArrayList<MessageExpiration.ExpiringMessage<NotificationMsg>>();

        var retained = expiration.retainNonExpired(empty);
        assertThat(retained).isEmpty();

        var extracted = expiration.extractExpired(empty);
        assertThat(extracted).isEmpty();

        var cleaned =
                expiration
                        .cleanupExpired(empty.stream())
                        .collect(Collectors.toList());
        assertThat(cleaned).isEmpty();
    }

    // -------------------------------------------------------
    // 16. Concurrent access (thread safety verification)
    // -------------------------------------------------------

    @Test
    void concurrentAccess_isThreadSafe() throws InterruptedException {
        var expiration = MessageExpiration.create(5000);

        var results = new ArrayList<Integer>();
        var barrier = new java.util.concurrent.CyclicBarrier(3);

        var t1 =
                new Thread(
                        () -> {
                            try {
                                barrier.await();
                                for (int i = 0; i < 50; i++) {
                                    var msg =
                                            expiration.withExpiration(
                                                    new NotificationMsg.Alert("T1-" + i, "HIGH"),
                                                    5000);
                                    results.add(expiration.isExpired(msg) ? 1 : 0);
                                }
                            } catch (Exception e) {
                                throw new RuntimeException(e);
                            }
                        });

        var t2 =
                new Thread(
                        () -> {
                            try {
                                barrier.await();
                                for (int i = 0; i < 50; i++) {
                                    var msg =
                                            expiration.withExpiration(
                                                    new NotificationMsg.Alert("T2-" + i, "MEDIUM"),
                                                    5000);
                                    results.add(expiration.ageMs(msg) >= 0 ? 1 : 0);
                                }
                            } catch (Exception e) {
                                throw new RuntimeException(e);
                            }
                        });

        var t3 =
                new Thread(
                        () -> {
                            try {
                                barrier.await();
                                for (int i = 0; i < 50; i++) {
                                    var msg =
                                            expiration.withExpiration(
                                                    new NotificationMsg.Alert("T3-" + i, "LOW"),
                                                    5000);
                                    results.add(expiration.timeUntilExpiration(msg) > 0 ? 1 : 0);
                                }
                            } catch (Exception e) {
                                throw new RuntimeException(e);
                            }
                        });

        t1.start();
        t2.start();
        t3.start();
        t1.join();
        t2.join();
        t3.join();

        // All threads completed successfully
        assertThat(results).hasSize(150);
    }
}
