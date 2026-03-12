package io.github.seanchatmangpt.jotp.dogfood.messaging;

import io.github.seanchatmangpt.jotp.ProcTimer;
import io.github.seanchatmangpt.jotp.messaging.system.MessageExpiration;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Runnable example demonstrating Message Expiration pattern.
 *
 * <p>Scenario: A distributed order notification system receives messages with
 * time-sensitive data (e.g., "order cancellation within 5 minutes of placement").
 * Messages that arrive late should not be processed as they represent stale state.
 * MessageExpiration ensures cleanup of expired notifications.
 */
public final class MessageExpirationExample {

    /**
     * Represents a notification message.
     */
    sealed interface NotificationMsg permits NotificationMsg.OrderNotification {
        record OrderNotification(String orderId, String action, String timestamp)
                implements NotificationMsg {
            OrderNotification {
                if (orderId == null || orderId.isBlank()) {
                    throw new IllegalArgumentException("orderId must not be blank");
                }
                if (action == null || action.isBlank()) {
                    throw new IllegalArgumentException("action must not be blank");
                }
            }
        }
    }

    /**
     * Main entry point demonstrating message expiration filtering.
     */
    public static void main(String[] args) throws InterruptedException {
        System.out.println("=== Message Expiration Example: TTL-based Filtering ===\n");

        // Create expiration manager
        var expiration = MessageExpiration.create(10000); // 10 second cleanup interval

        // Create a collection of incoming notifications with varying creation times
        var incomingNotifications = new ArrayList<MessageExpiration.ExpiringMessage<NotificationMsg>>();

        // Simulate notifications arriving with 5-second TTL
        System.out.println("Creating notifications with 5-second TTL:");
        for (int i = 1; i <= 4; i++) {
            var notification =
                    new NotificationMsg.OrderNotification(
                            "ORD-" + String.format("%03d", i * 100),
                            "cancellation_requested",
                            Instant.now().toString());

            var expiring = expiration.withExpiration(notification, 5000);
            incomingNotifications.add(expiring);
            System.out.println(
                    String.format(
                            "  [%d] Notification ORD-%03d: TTL 5s, expires at %s",
                            i, i * 100, expiring.expiresAt()));
        }

        System.out.println("\n→ Waiting 3 seconds (before expiration)...");
        Thread.sleep(3000);

        // Filter: should all still be valid
        System.out.println("\n=== Checking at 3s (before expiration) ===");
        var validAt3s = expiration.retainNonExpired(new ArrayList<>(incomingNotifications));
        System.out.println("Valid notifications: " + validAt3s.size());
        for (var msg : validAt3s) {
            System.out.println(
                    "  ✓ "
                            + msg.message()
                            + " (age: "
                            + expiration.ageMs(msg)
                            + "ms, ttl: "
                            + msg.ttlMs()
                            + "ms)");
        }

        System.out.println("\n→ Waiting 3 more seconds (past expiration)...");
        Thread.sleep(3000);

        // Filter: some should be expired now
        System.out.println("\n=== Checking at 6s (after expiration) ===");
        System.out.println("All messages at this point:");
        for (var msg : incomingNotifications) {
            boolean expired = expiration.isExpired(msg);
            String status = expired ? "✗ EXPIRED" : "✓ VALID";
            long age = expiration.ageMs(msg);
            long ttl = msg.ttlMs();
            System.out.println(
                    String.format(
                            "  %s %s (age: %dms, ttl: %dms)",
                            status,
                            msg.message().orderId(),
                            age,
                            ttl));
        }

        // Extract expired messages for archival/alerting
        var expiredMessages = expiration.extractExpired(incomingNotifications);
        System.out.println("\nExtracted expired messages: " + expiredMessages.size());
        for (var msg : expiredMessages) {
            System.out.println("  → Archiving: " + msg.message().orderId());
        }

        System.out.println("\nRemaining valid messages: " + incomingNotifications.size());
        for (var msg : incomingNotifications) {
            System.out.println("  → Processing: " + msg.message().orderId());
        }

        System.out.println("\n=== Time-until-expiration checks ===");
        // Create a fresh message and check countdown
        var freshMsg =
                new NotificationMsg.OrderNotification(
                        "ORD-999", "status_check", Instant.now().toString());
        var expiring = expiration.withExpiration(freshMsg, 2000);

        System.out.println("Fresh message TTL: 2000ms");
        System.out.println("Time until expiration: " + expiration.timeUntilExpiration(expiring) + "ms");

        System.out.println("Waiting 1.5 seconds...");
        Thread.sleep(1500);

        System.out.println("Time until expiration: " + expiration.timeUntilExpiration(expiring) + "ms");

        System.out.println("Waiting 1 more second (now expired)...");
        Thread.sleep(1000);

        boolean isNowExpired = expiration.isExpired(expiring);
        System.out.println("Is expired: " + isNowExpired);
        System.out.println("Time until expiration: " + expiration.timeUntilExpiration(expiring) + "ms");

        System.out.println("\n=== Example Complete ===");
    }
}
