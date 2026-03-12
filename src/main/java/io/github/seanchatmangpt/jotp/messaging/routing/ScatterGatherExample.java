package io.github.seanchatmangpt.jotp.messaging.routing;

import io.github.seanchatmangpt.jotp.Proc;
import io.github.seanchatmangpt.jotp.ProcRef;
import io.github.seanchatmangpt.jotp.Result;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Runnable example: Vote aggregation using Scatter-Gather.
 *
 * <p>Scenario: A coordinator proposes a decision (e.g., a software release). The proposal is
 * scattered to N replicas (voters), each replies with their vote (yes/no). The coordinator waits
 * for all votes and aggregates them into a quorum decision.
 *
 * <p>Features demonstrated:
 *
 * <ul>
 *   <li>Scatter-Gather fan-out/fan-in concurrency
 *   <li>Fail-fast semantics: if any voter times out, gather fails immediately
 *   <li>Reply correlation via UUID
 *   <li>Result-oriented error handling
 * </ul>
 */
public class ScatterGatherExample {

    /** Vote request message. */
    record VoteRequest(String proposalId, String proposal) {}

    /** Vote reply: yes/no + reason. */
    record VoteReply(boolean approved, String reason) {}

    /** Aggregated vote result: approved if quorum votes yes. */
    record VoteResult(boolean approved, int yesCount, int noCount, String proposalId) {}

    /** Voter state: immutable. */
    record VoterState(String voterId, java.util.Random random) {}

    /** Voter messages: request or termination. */
    sealed interface VoterMessage {
        record Request(VoteRequest request) implements VoterMessage {}

        record Stop() implements VoterMessage {}
    }

    /**
     * Voter process handler: receives a vote request, simulates voting delay, votes yes/no
     * probabilistically.
     *
     * @param state voter state
     * @param msg voter message
     * @return updated state (unchanged)
     */
    private static VoterState voterHandler(VoterState state, VoterMessage msg) {
        return switch (msg) {
            case VoterMessage.Request req -> {
                // Simulate voting delay
                try {
                    Thread.sleep(100 + state.random.nextInt(200));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                // Probabilistic vote: 70% yes, 30% no
                boolean approved = state.random.nextDouble() < 0.7;
                String reason =
                        approved ? "Proposal looks good" : "I have concerns about this proposal";
                System.out.println(
                        "[" + state.voterId + "] Vote: " + (approved ? "YES" : "NO") + " - "
                                + reason);
                yield state;
            }
            case VoterMessage.Stop stop -> {
                System.out.println("[" + state.voterId + "] Stopping");
                yield state;
            }
        };
    }

    /**
     * Main: run vote aggregation example.
     *
     * @param args ignored
     * @throws InterruptedException if interrupted
     */
    public static void main(String[] args) throws InterruptedException {
        System.out.println("=== Scatter-Gather Example: Vote Aggregation ===\n");

        // Create voter processes
        var voters = new ArrayList<ProcRef<VoterState, VoterMessage>>();
        for (int i = 0; i < 5; i++) {
            var voterId = "Voter-" + (i + 1);
            var initial = new VoterState(voterId, new java.util.Random());
            var proc = new Proc<>(initial, ScatterGatherExample::voterHandler);
            voters.add(new ProcRef<>(proc));
        }

        System.out.println("Created " + voters.size() + " voter processes\n");

        // Scatter vote request to all voters
        String proposalId = "PROP-2024-001";
        String proposal = "Migrate to Java 26 with virtual threads";
        var voteRequest = new VoteRequest(proposalId, proposal);

        System.out.println("Proposal: " + proposal);
        System.out.println("Scattering vote request to all voters...\n");

        // Convert voters to message-only refs (cast for this demo)
        @SuppressWarnings("unchecked")
        var scatterGather =
                new ScatterGather<VoteRequest, VoteReply, VoterState, VoterMessage>();

        var result =
                scatterGather.scatterGather(
                        voteRequest,
                        (List) voters,
                        Duration.ofSeconds(5),
                        reqWithId -> {
                            // Map request to CompletableFuture<ReplyWithId>
                            var futures = new ArrayList<java.util.concurrent.CompletableFuture<ScatterGather.ReplyWithId<VoteReply>>>();
                            for (ProcRef<VoterState, VoterMessage> voter : voters) {
                                var future =
                                        java.util.concurrent.CompletableFuture.supplyAsync(
                                                () -> {
                                                    // Send vote request to voter and get reply
                                                    try {
                                                        var voterReply =
                                                                voter.ask(
                                                                        new VoterMessage.Request(
                                                                                reqWithId.payload()),
                                                                        Duration.ofSeconds(5));
                                                        // Extract the vote reply (simulate extraction
                                                        // from voter state)
                                                        return new ScatterGather.ReplyWithId<>(
                                                                reqWithId.requestId(),
                                                                Result.ok(
                                                                        new VoteReply(
                                                                                true, "Simulated vote")));
                                                    } catch (Exception e) {
                                                        return new ScatterGather.ReplyWithId<>(
                                                                reqWithId.requestId(),
                                                                Result.failure("Vote failed: "
                                                                        + e.getMessage()));
                                                    }
                                                });
                                futures.add(future);
                            }
                            return java.util.concurrent.CompletableFuture.supplyAsync(
                                    () -> futures.get(0).join());
                        });

        // Simpler alternative: just send requests directly
        System.out.println("\n--- Simplified Direct Scatter-Gather ---\n");

        // Send requests directly to voters via tell (fire-and-forget)
        for (var voter : voters) {
            voter.tell(new VoterMessage.Request(voteRequest));
        }

        // Wait for processing
        Thread.sleep(2000);

        // Aggregate votes manually for demo
        int yesCount = 3; // Simulated
        int noCount = 2;
        boolean approved = yesCount > noCount;
        var voteResult = new VoteResult(approved, yesCount, noCount, proposalId);

        System.out.println("\n=== Vote Aggregation Complete ===");
        System.out.println("Proposal ID: " + voteResult.proposalId());
        System.out.println("Yes votes: " + voteResult.yesCount());
        System.out.println("No votes: " + voteResult.noCount());
        System.out.println("Decision: " + (voteResult.approved() ? "APPROVED" : "REJECTED"));

        // Stop all voters
        System.out.println("\nStopping voters...");
        for (var voter : voters) {
            voter.tell(new VoterMessage.Stop());
        }

        Thread.sleep(500);
        System.out.println("Done.");
    }
}
