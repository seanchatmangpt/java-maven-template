# 28. Normalizer

> *"A Normalizer converts semantically equivalent messages that arrive in different formats into a single canonical format."*

## Intent

When a system receives data from multiple sources — each with its own schema, field names, or encoding — the Normalizer translates each variant into a common canonical type before forwarding it downstream. Consumers see only the canonical type; the heterogeneity of sources is hidden behind the normalizer layer.

## OTP Analogy

In Erlang, normalization is typically a set of `handle_info` clauses or a function per incoming format:

```erlang
%% Normalize three different source formats to canonical order
normalize({legacy_order, Id, Amount, Curr}) ->
    #order{id = Id, amount = Amount, currency = Curr, source = legacy};
normalize({xml_order, XmlBin}) ->
    Parsed = parse_xml(XmlBin),
    #order{id = maps:get(order_id, Parsed), amount = maps:get(total, Parsed),
           currency = maps:get(currency, Parsed), source = xml};
normalize({json_order, JsonBin}) ->
    Parsed = jsx:decode(JsonBin, [return_maps]),
    #order{id = maps:get(<<"id">>, Parsed), ...}.
```

In JOTP, each source format gets its own `MessageTransformer<SourceFormat, CanonicalOrder>`.

## JOTP Implementation

One `MessageTransformer<T, CanonicalOrder>` per source format, all writing to the same downstream `MessageChannel<CanonicalOrder>`.

```
LegacyChannel ──► Transformer<LegacyOrder, CanonicalOrder>  ─┐
XmlChannel    ──► Transformer<XmlOrder,    CanonicalOrder>  ──┼──► CanonicalChannel<CanonicalOrder>
JsonChannel   ──► Transformer<JsonOrder,   CanonicalOrder>  ─┘
```

Key design points:
- Each transformer is a pure `Function<SourceType, CanonicalOrder>` — stateless and testable in isolation.
- The canonical type is the stable contract; source types may evolve without affecting downstream consumers.
- Validation belongs in the compact constructor of `CanonicalOrder` — invalid source data fails loudly at normalisation time, not deep downstream.
- A `DeadLetterChannel` on each transformer captures source messages that fail conversion.

## API Reference

| Class / Method | Description |
|----------------|-------------|
| `new MessageTransformer<>(fn, downstream)` | Normalizer for one source format |
| `canonical.send(msg)` | Downstream channel for all normalised messages |
| `fn.apply(source)` | Pure conversion function |

## Code Example

```java
import org.acme.reactive.MessageTransformer;
import org.acme.reactive.PointToPointChannel;

// --- Canonical type: all sources normalise to this ---
record CanonicalOrder(
    String  orderId,
    double  amount,
    String  currency,
    String  customerId,
    Instant receivedAt,
    String  sourceSystem   // for audit/diagnostics
) {
    CanonicalOrder {
        Objects.requireNonNull(orderId,     "orderId required");
        Objects.requireNonNull(currency,    "currency required");
        Objects.requireNonNull(customerId,  "customerId required");
        if (amount <= 0) throw new IllegalArgumentException("amount must be positive");
    }
}

// --- Source format 1: legacy CSV-like struct ---
record LegacyOrder(String order_id, String amount_str, String ccy, String cust_no) {}

// --- Source format 2: XML-parsed map ---
record XmlOrder(Map<String, String> fields) {}

// --- Source format 3: JSON-parsed record ---
record JsonOrder(String id, double total, String currency, String customer_id) {}

// --- Normalizer functions (one per source) ---
class LegacyNormalizer {
    static CanonicalOrder normalize(LegacyOrder src) {
        return new CanonicalOrder(
            src.order_id(),
            Double.parseDouble(src.amount_str().replace(",", ".")),
            src.ccy().toUpperCase(),
            src.cust_no(),
            Instant.now(),
            "LEGACY"
        );
    }
}

class XmlNormalizer {
    static CanonicalOrder normalize(XmlOrder src) {
        return new CanonicalOrder(
            src.fields().get("OrderId"),
            Double.parseDouble(src.fields().get("TotalAmount")),
            src.fields().get("Currency"),
            src.fields().get("CustomerRef"),
            Instant.now(),
            "XML"
        );
    }
}

class JsonNormalizer {
    static CanonicalOrder normalize(JsonOrder src) {
        return new CanonicalOrder(
            src.id(),
            src.total(),
            src.currency(),
            src.customer_id(),
            Instant.now(),
            "JSON"
        );
    }
}

public class NormalizerDemo {

    public static void main(String[] args) throws InterruptedException {
        // Shared canonical downstream channel
        var canonicalChannel = new PointToPointChannel<CanonicalOrder>(order ->
            System.out.printf("[CANONICAL] id=%s amt=%.2f %s cust=%s src=%s%n",
                order.orderId(), order.amount(), order.currency(),
                order.customerId(), order.sourceSystem())
        );

        // One normalizer per source format, all writing to canonicalChannel
        MessageChannel<LegacyOrder> legacyNorm = new MessageTransformer<>(
            LegacyNormalizer::normalize, canonicalChannel);

        MessageChannel<XmlOrder> xmlNorm = new MessageTransformer<>(
            XmlNormalizer::normalize, canonicalChannel);

        MessageChannel<JsonOrder> jsonNorm = new MessageTransformer<>(
            JsonNormalizer::normalize, canonicalChannel);

        // Simulate messages from 3 different source systems
        legacyNorm.send(new LegacyOrder("L-001", "149,99", "eur", "C-001"));

        xmlNorm.send(new XmlOrder(Map.of(
            "OrderId", "X-001",
            "TotalAmount", "299.00",
            "Currency", "USD",
            "CustomerRef", "C-002"
        )));

        jsonNorm.send(new JsonOrder("J-001", 59.95, "GBP", "C-003"));

        // All arrive at canonical channel in a uniform format
        legacyNorm.stop();
        xmlNorm.stop();
        jsonNorm.stop();
    }
}
```

## Test Pattern

```java
import org.junit.jupiter.api.Test;
import org.assertj.core.api.WithAssertions;

class NormalizerTest implements WithAssertions {

    @Test
    void legacyOrderNormalizesToCanonical() {
        var src    = new LegacyOrder("L-T1", "99,50", "eur", "CUST-1");
        var result = LegacyNormalizer.normalize(src);

        assertThat(result.orderId()).isEqualTo("L-T1");
        assertThat(result.amount()).isEqualTo(99.50);
        assertThat(result.currency()).isEqualTo("EUR");  // uppercased
        assertThat(result.customerId()).isEqualTo("CUST-1");
        assertThat(result.sourceSystem()).isEqualTo("LEGACY");
    }

    @Test
    void xmlOrderNormalizesToCanonical() {
        var src = new XmlOrder(Map.of(
            "OrderId", "X-T1", "TotalAmount", "50.0",
            "Currency", "USD", "CustomerRef", "CUST-2"
        ));
        var result = XmlNormalizer.normalize(src);

        assertThat(result.orderId()).isEqualTo("X-T1");
        assertThat(result.amount()).isEqualTo(50.0);
        assertThat(result.sourceSystem()).isEqualTo("XML");
    }

    @Test
    void jsonOrderNormalizesToCanonical() {
        var src    = new JsonOrder("J-T1", 25.0, "GBP", "CUST-3");
        var result = JsonNormalizer.normalize(src);

        assertThat(result.orderId()).isEqualTo("J-T1");
        assertThat(result.currency()).isEqualTo("GBP");
        assertThat(result.sourceSystem()).isEqualTo("JSON");
    }

    @Test
    void canonicalOrderRejectsNegativeAmount() {
        assertThatThrownBy(() -> new CanonicalOrder(
            "X", -1.0, "USD", "C", Instant.now(), "TEST"
        )).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void allSourceFormatsProduceSameCanonicalShape() {
        var legacy = LegacyNormalizer.normalize(
            new LegacyOrder("SAME-001", "100,00", "usd", "CUST-X"));
        var json   = JsonNormalizer.normalize(
            new JsonOrder("SAME-001", 100.00, "USD", "CUST-X"));

        // Different sources, same canonical content
        assertThat(legacy.orderId()).isEqualTo(json.orderId());
        assertThat(legacy.amount()).isEqualTo(json.amount());
        assertThat(legacy.currency()).isEqualTo(json.currency());
    }
}
```

## Caveats & Trade-offs

**Use when:**
- Multiple external systems send semantically identical data in different schemas.
- Downstream consumers must not be coupled to source-specific formats.
- You need a single integration point to apply validation and enrichment rules.

**Avoid when:**
- Source formats are genuinely different domain concepts — forcing them into one canonical type loses semantic distinctions.
- The canonical type becomes a kitchen-sink record with nullable fields for every source — prefer separate canonical types per domain concept.
- Normalisation requires external lookups (e.g., code tables) — use `ContentEnricher` instead of or in addition to `MessageTransformer`.
