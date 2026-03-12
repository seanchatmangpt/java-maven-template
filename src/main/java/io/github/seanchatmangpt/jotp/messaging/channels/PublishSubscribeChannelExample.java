package io.github.seanchatmangpt.jotp.messaging.channels;

import io.github.seanchatmangpt.jotp.messaging.Message;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Runnable example of the Publish-Subscribe Channel pattern.
 *
 * <p>Demonstrates a stock price update system where:
 * 1. StockPriceService publishes price updates
 * 2. Multiple subscribers (Dashboard, Analytics, Logger) receive updates independently
 * 3. Each subscriber processes the event according to its own logic
 *
 * <p>Run with: java io.github.seanchatmangpt.jotp.messaging.channels.PublishSubscribeChannelExample
 */
public class PublishSubscribeChannelExample {

    public static void main(String[] args) throws InterruptedException {
        System.out.println("=== Publish-Subscribe Channel Example ===");
        System.out.println("Demonstrating 1:N event broadcasting.\n");

        // Create a pub-sub channel for stock price updates
        var stockChannel = PublishSubscribeChannel.<Message>create();

        // Subscriber 1: Dashboard (updates display)
        var dashboard = new Dashboard();
        stockChannel.subscribe(msg -> {
            if (msg instanceof Message.EventMsg evt && "PRICE_UPDATE".equals(evt.eventType())) {
                dashboard.updatePrice(evt.payload());
            }
        });

        // Subscriber 2: Analytics (logs for analysis)
        var analytics = new Analytics();
        stockChannel.subscribe(msg -> {
            if (msg instanceof Message.EventMsg evt && "PRICE_UPDATE".equals(evt.eventType())) {
                analytics.recordUpdate(evt.payload());
            }
        });

        // Subscriber 3: Alert Service (checks thresholds)
        var alertService = new AlertService();
        stockChannel.subscribe(msg -> {
            if (msg instanceof Message.EventMsg evt && "PRICE_UPDATE".equals(evt.eventType())) {
                alertService.checkAlert(evt.payload());
            }
        });

        System.out.println("Subscribers registered: " + stockChannel.subscriberCount() + "\n");

        // Publish price updates (all subscribers notified)
        System.out.println("Publishing stock price updates...\n");

        var prices = new Object[]{
            Map.of("symbol", "AAPL", "price", 150.25),
            Map.of("symbol", "AAPL", "price", 151.00),
            Map.of("symbol", "AAPL", "price", 149.75)
        };

        for (var price : prices) {
            var event = Message.event("PRICE_UPDATE", price);
            stockChannel.publish(event);
            Thread.sleep(100);
        }

        Thread.sleep(200); // Let async processing settle

        // Display results
        System.out.println("\n[Dashboard] " + dashboard);
        System.out.println("[Analytics] " + analytics);
        System.out.println("[AlertService] " + alertService);
        System.out.println("\n=== Example Complete ===");
    }

    static class Dashboard {
        Map<String, Double> prices = new ConcurrentHashMap<>();

        void updatePrice(Object payload) {
            if (payload instanceof Map<?, ?> m) {
                var symbol = String.valueOf(m.get("symbol"));
                var price = Double.parseDouble(String.valueOf(m.get("price")));
                prices.put(symbol, price);
                System.out.println("[Dashboard] Updated " + symbol + " to $" + price);
            }
        }

        @Override
        public String toString() {
            return "Prices=" + prices;
        }
    }

    static class Analytics {
        List<Object> updates = Collections.synchronizedList(new ArrayList<>());
        int updateCount = 0;

        void recordUpdate(Object payload) {
            updates.add(payload);
            updateCount++;
            System.out.println("[Analytics] Recorded update #" + updateCount);
        }

        @Override
        public String toString() {
            return "RecordedUpdates=" + updateCount;
        }
    }

    static class AlertService {
        static final double ALERT_THRESHOLD = 150.0;
        List<String> alerts = Collections.synchronizedList(new ArrayList<>());

        void checkAlert(Object payload) {
            if (payload instanceof Map<?, ?> m) {
                var symbol = String.valueOf(m.get("symbol"));
                var price = Double.parseDouble(String.valueOf(m.get("price")));

                if (price > ALERT_THRESHOLD) {
                    var alert = symbol + " exceeded threshold: $" + price;
                    alerts.add(alert);
                    System.out.println("[AlertService] ALERT: " + alert);
                }
            }
        }

        @Override
        public String toString() {
            return "Alerts=" + alerts.size();
        }
    }
}
