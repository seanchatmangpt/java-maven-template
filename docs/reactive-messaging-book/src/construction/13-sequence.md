# 13. Message Sequence

> *"A Message Sequence breaks large data sets into a series of related messages, numbered so receivers can detect missing messages and reorder them."*

## Intent

When a payload is too large to send as a single message, or when a splitter explodes a composite into parts, each part is tagged with a sequence number. A downstream Resequencer buffers out-of-order parts and delivers them in the correct order.

## OTP Analogy

Erlang TCP/IP layer (and many OTP protocols) rely on sequence numbers for reliable delivery. A custom protocol in Erlang would tag messages with a sequence counter:

```erlang
%% Producer assigns monotonically increasing sequence numbers
send_chunk(Pid, SeqN, Chunk) ->
    Pid ! {chunk, #{seq => SeqN, payload => Chunk, total => 100}}.

%% Receiver buffers and reorders
handle_info({chunk, #{seq := N, payload := P}}, State) ->
    Buffer = maps:put(N, P, State#state.buffer),
    {noreply, State#state{buffer = Buffer}}.
```

JOTP wraps this pattern in a generic `record Seq<T>` and a `Resequencer<T, K>` that restores order.

## JOTP Implementation

Two cooperating components:

1. **Producer side** — wraps each part in `Seq<T>`:
   ```
   large payload
       │
   MessageSplitter<T,U>
       │
   Seq<U>(n=1), Seq<U>(n=2), … Seq<U>(n=N)
       │
   PointToPointChannel<Seq<U>>   (concurrent delivery, may arrive out of order)
   ```

2. **Consumer side** — `Resequencer<T, K>` buffers and re-emits in order:
   ```
   Resequencer<Seq<U>, Integer>
       ├── receive Seq(n=3) → buffer
       ├── receive Seq(n=1) → buffer, emit 1
       ├── receive Seq(n=2) → emit 2, then 3 (gap filled)
       └── emit N → done
   ```

`Resequencer<T,K>` is backed by a `Proc` with a `TreeMap<K, T>` state — messages are sorted by key `K` and emitted in ascending key order as gaps are filled.

Key design points:
- `Seq<T>` is a generic wrapper: `record Seq<T>(int n, int total, T payload)`.
- `total` lets the resequencer know when all parts have been received.
- Use `UUID correlationId` on `Seq<T>` when multiple sequences are interleaved on the same channel.
- The resequencer emits a part only when all predecessors have been received.

## API Reference

| Class / Method | Description |
|----------------|-------------|
| `record Seq<T>(int n, int total, UUID seqId, T payload)` | Sequence envelope |
| `new Resequencer<>(keyFn, consumer)` | Create resequencer |
| `resequencer.accept(seq)` | Feed a (possibly out-of-order) message |
| `new MessageSplitter<>(splitFn, channel)` | Split composite → numbered parts |

## Code Example

```java
import org.acme.reactive.Resequencer;

// --- Generic sequence envelope ---
record Seq<T>(int n, int total, UUID seqId, T payload) {
    boolean isLast() { return n == total; }
}

// --- A chunked file transfer scenario ---
record FileChunk(String filename, byte[] data) {}

public class SequenceDemo {

    // Producer: number each chunk
    static List<Seq<FileChunk>> chunkFile(String filename, byte[] content, int chunkSize) {
        var chunks = new ArrayList<Seq<FileChunk>>();
        var seqId  = UUID.randomUUID();
        int total  = (int) Math.ceil((double) content.length / chunkSize);

        for (int i = 0; i < total; i++) {
            int from = i * chunkSize;
            int to   = Math.min(from + chunkSize, content.length);
            chunks.add(new Seq<>(i + 1, total, seqId,
                new FileChunk(filename, Arrays.copyOfRange(content, from, to))));
        }
        return chunks;
    }

    // Consumer: reassemble in order
    static void demo() throws Exception {
        var received  = new CopyOnWriteArrayList<Seq<FileChunk>>();
        var assembled = new CompletableFuture<byte[]>();

        // Resequencer delivers parts to consumer in n=1,2,3,… order
        Resequencer<Seq<FileChunk>, Integer> reseq = new Resequencer<>(
            seq  -> seq.n(),   // order key
            seq  -> {          // in-order consumer
                received.add(seq);
                if (seq.isLast()) {
                    byte[] full = received.stream()
                        .sorted(Comparator.comparingInt(Seq::n))
                        .map(s -> s.payload().data())
                        .reduce(new byte[0], SequenceDemo::concat);
                    assembled.complete(full);
                }
            }
        );

        // Simulate out-of-order delivery
        byte[] data   = "Hello, JOTP sequence!".getBytes();
        var    chunks = chunkFile("test.txt", data, 5);
        Collections.shuffle(chunks);  // deliver out of order

        for (var chunk : chunks) reseq.accept(chunk);

        byte[] result = assembled.get(2, TimeUnit.SECONDS);
        System.out.println("Reassembled: " + new String(result));
    }

    static byte[] concat(byte[] a, byte[] b) {
        var r = Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, r, a.length, b.length);
        return r;
    }
}
```

## Test Pattern

```java
import org.junit.jupiter.api.Test;
import org.assertj.core.api.WithAssertions;
import java.util.concurrent.CopyOnWriteArrayList;

class MessageSequenceTest implements WithAssertions {

    @Test
    void resequencerDeliversInOrder() throws Exception {
        var ordered = new CopyOnWriteArrayList<Integer>();
        var seqId   = UUID.randomUUID();

        Resequencer<Seq<String>, Integer> reseq = new Resequencer<>(
            Seq::n,
            seq -> ordered.add(seq.n())
        );

        // Deliver out of order: 3, 1, 2
        reseq.accept(new Seq<>(3, 3, seqId, "third"));
        reseq.accept(new Seq<>(1, 3, seqId, "first"));
        reseq.accept(new Seq<>(2, 3, seqId, "second"));

        // Give resequencer a moment to emit
        Thread.sleep(50);
        assertThat(ordered).containsExactly(1, 2, 3);
    }

    @Test
    void seqEnvelopeReportsLastCorrectly() {
        var seqId = UUID.randomUUID();
        assertThat(new Seq<>(5, 5, seqId, "x").isLast()).isTrue();
        assertThat(new Seq<>(4, 5, seqId, "x").isLast()).isFalse();
    }
}
```

## Caveats & Trade-offs

**Use when:**
- A large payload is split into parts for concurrent or chunked delivery.
- Parts may arrive out of order due to concurrent processing or network reordering.
- You need exactly-once, in-order delivery semantics within a single process.

**Avoid when:**
- Parts always arrive in order — resequencing adds buffering overhead for no benefit.
- Parts can arrive arbitrarily late — unbounded buffer growth is a risk; implement a TTL or maximum window size.
- Cross-process or cross-restart ordering is required — use a durable log (Kafka partition ordering) instead of the in-memory `Resequencer`.
