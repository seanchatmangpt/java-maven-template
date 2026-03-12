package io.github.seanchatmangpt.jotp.messaging.routing;

import io.github.seanchatmangpt.jotp.Parallel;
import io.github.seanchatmangpt.jotp.ProcRef;
import io.github.seanchatmangpt.jotp.Result;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;

/**
 * Scatter-Gather messaging pattern: fan-out a request to multiple recipients and collect all
 * replies.
 *
 * <p>Vernon: "Send a message to multiple recipients simultaneously, wait for all replies to arrive
 * before proceeding. If any recipient fails to reply within the timeout or responds with an error,
 * the entire gather operation fails (fail-fast semantics)."
 *
 * <p>Uses JOTP's {@link Parallel} for concurrent request-reply with automatic cancellation of
 * pending tasks on the first failure.
 *
 * <p>Example:
 *
 * <pre>{@code
 * var sg = new ScatterGather<>();
 * var request = new VoteRequest(proposalId, deadline);
 * var replies = sg.scatterGather(request, voterRefs, Duration.ofSeconds(5));
 * // Returns List<VoteReply> or fails fast on first timeout/error
 * }</pre>
 *
 * @param <Req> request message type
 * @param <Rep> reply message type
 * @param <S> process state type (generic, not used in scatter-gather but kept for flexibility)
 * @param <M> process message type
 */
public class ScatterGather<Req, Rep, S, M> {

    /**
     * Request wrapper: correlates outgoing requests with incoming replies via requestId.
     *
     * <p>Each request carries a unique ID that the recipient echoes back in the reply, enabling
     * correlation even if replies arrive out-of-order.
     *
     * @param <Req> request payload type
     */
    public record RequestWithId<Req>(String requestId, Req payload) {}

    /**
     * Reply wrapper: correlates with the original request via requestId.
     *
     * <p>The reply may succeed (carrying a value) or fail (carrying an error), signaling whether
     * this recipient's contribution to the gather is valid.
     *
     * @param <Rep> reply payload type
     */
    public record ReplyWithId<Rep>(String requestId, Result<Rep, String> result) {}

    /**
     * Scatter-Gather: send request to all recipients and collect replies.
     *
     * <p>Sends {@code request} to each recipient in {@code recipients}, waits for all to reply
     * within {@code timeout}, and returns the results in the order of recipients. If any recipient
     * times out or responds with a failure result, the entire operation fails immediately
     * (fail-fast).
     *
     * @param request the request to scatter
     * @param recipients list of process references to send to
     * @param timeout maximum time to wait for all replies
     * @param requestMapper function to wrap the request with ID and send to recipient
     * @return {@code Result.success(replies)} with replies in recipient order, or {@code
     *     Result.failure(exception)} on first timeout/failure
     */
    public Result<List<Rep>, Exception> scatterGather(
            Req request,
            List<ProcRef<S, M>> recipients,
            Duration timeout,
            Function<RequestWithId<Req>, CompletableFuture<ReplyWithId<Rep>>> requestMapper) {
        try {
            var requestId = UUID.randomUUID().toString();
            var requestWithId = new RequestWithId<>(requestId, request);

            // Create tasks: each task sends the request and waits for the reply
            var tasks =
                    recipients.stream()
                            .map(
                                    recipient ->
                                            (java.util.function.Supplier<Rep>)
                                                    () -> {
                                                        try {
                                                            var replyFuture =
                                                                    requestMapper.apply(
                                                                            requestWithId);
                                                            var reply =
                                                                    replyFuture
                                                                            .orTimeout(
                                                                                    timeout
                                                                                            .toMillis(),
                                                                                    java.util
                                                                                            .concurrent
                                                                                            .TimeUnit
                                                                                            .MILLISECONDS)
                                                                            .join();

                                                            // Check if reply is successful
                                                            return switch (reply.result) {
                                                                case Result.Ok<Rep, String> ok ->
                                                                        ok.value;
                                                                case Result.Success<Rep, String> success ->
                                                                        success.value;
                                                                case Result.Err<Rep, String> err ->
                                                                        throw new RuntimeException(
                                                                                "Recipient error: "
                                                                                        + err
                                                                                                .error);
                                                                case Result.Failure<Rep, String> failure ->
                                                                        throw new RuntimeException(
                                                                                "Recipient error: "
                                                                                        + failure
                                                                                                .error);
                                                            };
                                                        } catch (java.util.concurrent.TimeoutException
                                                                e) {
                                                            throw new RuntimeException(
                                                                    "Reply timeout", e);
                                                        } catch (Exception e) {
                                                            throw new RuntimeException(
                                                                    "Reply failed", e);
                                                        }
                                                    })
                            .toList();

            // Use Parallel.all for fail-fast semantics
            return Parallel.all(tasks);
        } catch (Exception e) {
            return Result.failure(e);
        }
    }

    /**
     * Aggregate replies using a custom aggregator function.
     *
     * <p>After gathering all replies, apply a reduction function to combine them into a single
     * result. Useful for voting, consensus, or other aggregation patterns.
     *
     * <p>Example: sum votes, find consensus, validate quorum.
     *
     * @param replies list of gathered replies
     * @param identity initial aggregation value
     * @param aggregator {@code (accumulator, reply) -> nextAccumulator}
     * @param <Agg> aggregation result type
     * @return aggregated result
     */
    public <Agg> Agg collectWith(
            List<Rep> replies, Agg identity, java.util.function.BiFunction<Agg, Rep, Agg> aggregator) {
        var result = identity;
        for (Rep reply : replies) {
            result = aggregator.apply(result, reply);
        }
        return result;
    }
}
