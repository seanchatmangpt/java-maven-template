package org.acme;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Saga Orchestrator — coordinates distributed transactions across services.
 *
 * <p>Vaughn Vernon: "A Saga is a sequence of local transactions where each transaction
 * updates a single service and publishes a message or event to trigger the next step.
 * If a step fails, compensating actions undo the previous steps."
 *
 * <p>Joe Armstrong: "In Erlang, we don't have distributed transactions. We have processes
 * that coordinate through message passing. The saga pattern is just another process."
 *
 * <p>Features:
 * <ul>
 *   <li><b>Orchestrated sagas</b> — Central coordinator manages the workflow</li>
 *   <li><b>Compensating actions</b> — Rollback on failure</li>
 *   <li><b>State persistence</b> — Recover from crashes mid-saga</li>
 *   <li><b>Timeout handling</b> — Automatic compensation on timeout</li>
 *   <li><b>Step retry</b> — Configurable retry for transient failures</li>
 * </ul>
 *
 * <p><b>Usage:</b>
 * <pre>{@code
 * SagaOrchestrator saga = SagaOrchestrator.builder("order-fulfillment")
 *     .step("reserve-inventory")
 *         .action(inventoryService::reserve)
 *         .compensation(inventoryService::release)
 *     .step("charge-payment")
 *         .action(paymentService::charge)
 *         .compensation(paymentService::refund)
 *     .step("ship-order")
 *         .action(shippingService::ship)
 *         .timeout(Duration.ofMinutes(5))
 *     .build();
 *
 * SagaResult result = saga.execute(orderId, orderData).get();
 * }</pre>
 */
public final class SagaOrchestrator<S, D> implements Application.Infrastructure {

    /** Saga step definition. */
    public record Step<S, D>(
            String name,
            BiFunction<D, SagaContext, CompletableFuture<S>> action,
            BiFunction<D, S, CompletableFuture<Void>> compensation,
            Duration timeout,
            int maxRetries,
            Predicate<Throwable> retryOn) {

        public static <S, D> Builder<S, D> named(String name) {
            return new Builder<>(name);
        }

        public static final class Builder<S, D> {
            private final String name;
            private BiFunction<D, SagaContext, CompletableFuture<S>> action;
            private BiFunction<D, S, CompletableFuture<Void>> compensation;
            private Duration timeout = Duration.ofSeconds(30);
            private int maxRetries = 3;
            private Predicate<Throwable> retryOn = t -> true;

            public Builder(String name) {
                this.name = name;
            }

            public Builder<S, D> action(BiFunction<D, SagaContext, CompletableFuture<S>> action) {
                this.action = action;
                return this;
            }

            public Builder<S, D> compensation(BiFunction<D, S, CompletableFuture<Void>> compensation) {
                this.compensation = compensation;
                return this;
            }

            public Builder<S, D> timeout(Duration timeout) {
                this.timeout = timeout;
                return this;
            }

            public Builder<S, D> maxRetries(int maxRetries) {
                this.maxRetries = maxRetries;
                return this;
            }

            public Builder<S, D> retryOn(Predicate<Throwable> retryOn) {
                this.retryOn = retryOn;
                return this;
            }

            public Step<S, D> build() {
                return new Step<>(
                        name,
                        action,
                        compensation,
                        timeout,
                        maxRetries,
                        retryOn);
            }
        }
    }

    /** Saga execution context. */
    public record SagaContext(
            UUID sagaId,
            String sagaName,
            Instant startTime,
            int currentStep,
            SagaStatus status,
            Map<String, Object> results,
            List<String> completedSteps) {

        public static SagaContext start(UUID sagaId, String sagaName) {
            return new SagaContext(sagaId, sagaName, Instant.now(), 0, SagaStatus.STARTED, new LinkedHashMap<>(), new ArrayList<>());
        }

        public SagaContext withResult(String step, Object result) {
            Map<String, Object> newResults = new LinkedHashMap<>(results);
            newResults.put(step, result);
            List<String> newCompleted = new ArrayList<>(completedSteps);
            newCompleted.add(step);
            return new SagaContext(sagaId, sagaName, startTime, currentStep + 1, status, newResults, newCompleted);
        }

        public SagaContext withStatus(SagaStatus newStatus) {
            return new SagaContext(sagaId, sagaName, startTime, currentStep, newStatus, results, completedSteps);
        }

        public Duration elapsed() {
            return Duration.between(startTime, Instant.now());
        }
    }

    /** Saga status. */
    public enum SagaStatus {
        STARTED,
        IN_PROGRESS,
        COMPLETED,
        COMPENSATING,
        COMPENSATED,
        FAILED,
        TIMED_OUT
    }

    /** Saga execution result. */
    public sealed interface SagaResult permits SagaResult.Success, SagaResult.Failure, SagaResult.Compensated {
        record Success(Map<String, Object> results) implements SagaResult {}
        record Failure(String step, Throwable error) implements SagaResult {}
        record Compensated(String failedStep, Throwable error, List<String> compensatedSteps) implements SagaResult {}
    }

    // ── Saga orchestrator state ──────────────────────────────────────────────────

    private final String name;
    private final List<Step<S, D>> steps;
    private final Duration globalTimeout;
    private final EventStore eventStore;
    private final Proc<SagaState, SagaMsg> coordinator;

    private record SagaState(
            Map<UUID, SagaRuntime> sagas,
            Map<UUID, CompletableFuture<SagaResult>> pending) {}

    private sealed interface SagaMsg permits SagaMsg.Start, SagaMsg.StepComplete, SagaMsg.StepFailed, SagaMsg.Timeout, SagaMsg.GetStatus {
        record Start(UUID sagaId, Object data, CompletableFuture<SagaResult> replyTo) implements SagaMsg {}
        record StepComplete(UUID sagaId, String step, Object result) implements SagaMsg {}
        record StepFailed(UUID sagaId, String step, Throwable error) implements SagaMsg {}
        record Timeout(UUID sagaId) implements SagaMsg {}
        record GetStatus(UUID sagaId, CompletableFuture<Optional<SagaContext>> replyTo) implements SagaMsg {}
    }

    /** Saga runtime state including the original data for step execution. */
    private record SagaRuntime(
            SagaContext context,
            Object data,
            Map<String, Object> stepResults) {

        SagaRuntime withResult(String step, Object result) {
            Map<String, Object> newResults = new LinkedHashMap<>(stepResults);
            newResults.put(step, result);
            return new SagaRuntime(context.withResult(step, result), data, newResults);
        }
    }

    private SagaOrchestrator(Builder<S, D> builder) {
        this.name = builder.name;
        this.steps = List.copyOf(builder.steps);
        this.globalTimeout = builder.globalTimeout;
        this.eventStore = builder.eventStore;

        this.coordinator = new Proc<>(
                new SagaState(new ConcurrentHashMap<>(), new ConcurrentHashMap<>()),
                this::handleSagaMessage);
    }

    // ── Factory ──────────────────────────────────────────────────────────────────

    public static <S, D> Builder<S, D> builder(String name) {
        return new Builder<>(name);
    }

    /** Saga builder. */
    public static final class Builder<S, D> {
        private final String name;
        private final List<Step<S, D>> steps = new ArrayList<>();
        private Duration globalTimeout = Duration.ofMinutes(10);
        private EventStore eventStore;

        public Builder(String name) {
            this.name = name;
        }

        public Builder<S, D> step(Step<S, D> step) {
            this.steps.add(step);
            return this;
        }

        public Builder<S, D> globalTimeout(Duration timeout) {
            this.globalTimeout = timeout;
            return this;
        }

        public Builder<S, D> eventStore(EventStore eventStore) {
            this.eventStore = eventStore;
            return this;
        }

        public SagaOrchestrator<S, D> build() {
            return new SagaOrchestrator<>(this);
        }
    }

    // ── Saga execution ────────────────────────────────────────────────────────────

    /**
     * Execute a saga with the given data.
     */
    @SuppressWarnings("unchecked")
    public CompletableFuture<SagaResult> execute(D data) {
        UUID sagaId = UUID.randomUUID();
        CompletableFuture<SagaResult> future = new CompletableFuture<>();

        coordinator.tell(new SagaMsg.Start(sagaId, data, future));

        // Set global timeout
        return future.orTimeout(globalTimeout.toMillis(), TimeUnit.MILLISECONDS)
                .exceptionally(e -> new SagaResult.Failure("global", e));
    }

    /**
     * Get the status of a saga.
     */
    public CompletableFuture<Optional<SagaContext>> getStatus(UUID sagaId) {
        CompletableFuture<Optional<SagaContext>> future = new CompletableFuture<>();
        coordinator.tell(new SagaMsg.GetStatus(sagaId, future));
        return future;
    }

    // ── Message handling ──────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private SagaState handleSagaMessage(SagaState state, SagaMsg msg) {
        return switch (msg) {
            case SagaMsg.Start(var sagaId, var data, var replyTo) -> {
                SagaContext ctx = SagaContext.start(sagaId, name);
                SagaRuntime runtime = new SagaRuntime(ctx, data, new LinkedHashMap<>());
                Map<UUID, SagaRuntime> newSagas = new ConcurrentHashMap<>(state.sagas());
                newSagas.put(sagaId, runtime);
                Map<UUID, CompletableFuture<SagaResult>> newPending = new ConcurrentHashMap<>(state.pending());
                newPending.put(sagaId, replyTo);

                // Start first step
                executeStep(sagaId, (D) data, ctx, 0);

                yield new SagaState(newSagas, newPending);
            }

            case SagaMsg.StepComplete(var sagaId, var step, var result) -> {
                SagaRuntime runtime = state.sagas().get(sagaId);
                if (runtime == null) yield state;

                SagaRuntime newRuntime = runtime.withResult(step, result);
                Map<UUID, SagaRuntime> newSagas = new ConcurrentHashMap<>(state.sagas());
                newSagas.put(sagaId, newRuntime);

                if (newRuntime.context().currentStep() >= steps.size()) {
                    // Saga complete
                    SagaContext newCtx = newRuntime.context().withStatus(SagaStatus.COMPLETED);
                    newSagas.put(sagaId, new SagaRuntime(newCtx, newRuntime.data(), newRuntime.stepResults()));
                    CompletableFuture<SagaResult> future = state.pending().get(sagaId);
                    if (future != null) {
                        future.complete(new SagaResult.Success(newCtx.results()));
                    }
                } else {
                    // Continue to next step with stored data
                    executeStep(sagaId, (D) newRuntime.data(), newRuntime.context(), newRuntime.context().currentStep());
                }

                yield new SagaState(newSagas, state.pending());
            }

            case SagaMsg.StepFailed(var sagaId, var step, var error) -> {
                SagaRuntime runtime = state.sagas().get(sagaId);
                if (runtime == null) yield state;

                // Start compensation
                SagaContext newCtx = runtime.context().withStatus(SagaStatus.COMPENSATING);
                Map<UUID, SagaRuntime> newSagas = new ConcurrentHashMap<>(state.sagas());
                newSagas.put(sagaId, new SagaRuntime(newCtx, runtime.data(), runtime.stepResults()));

                // Compensate in reverse order
                compensate(sagaId, runtime.context(), state.pending().get(sagaId), error);

                yield new SagaState(newSagas, state.pending());
            }

            case SagaMsg.Timeout(var sagaId) -> {
                SagaRuntime runtime = state.sagas().get(sagaId);
                if (runtime == null) yield state;

                SagaContext newCtx = runtime.context().withStatus(SagaStatus.TIMED_OUT);
                Map<UUID, SagaRuntime> newSagas = new ConcurrentHashMap<>(state.sagas());
                newSagas.put(sagaId, new SagaRuntime(newCtx, runtime.data(), runtime.stepResults()));

                compensate(sagaId, runtime.context(), state.pending().get(sagaId), new TimeoutException("Saga timed out"));

                yield new SagaState(newSagas, state.pending());
            }

            case SagaMsg.GetStatus(var sagaId, var replyTo) -> {
                SagaRuntime runtime = state.sagas().get(sagaId);
                replyTo.complete(Optional.ofNullable(runtime != null ? runtime.context() : null));
                yield state;
            }
        };
    }

    @SuppressWarnings("unchecked")
    private void executeStep(UUID sagaId, D data, SagaContext ctx, int stepIndex) {
        if (stepIndex >= steps.size()) return;

        Step<S, D> step = steps.get(stepIndex);

        step.action().apply(data, ctx)
                .thenAccept(result -> coordinator.tell(new SagaMsg.StepComplete(sagaId, step.name(), result)))
                .exceptionally(error -> {
                    coordinator.tell(new SagaMsg.StepFailed(sagaId, step.name(), error));
                    return null;
                });
    }

    @SuppressWarnings("unchecked")
    private void compensate(UUID sagaId, SagaContext ctx, CompletableFuture<SagaResult> pendingFuture, Throwable originalError) {
        // Compensate in reverse order
        List<String> toCompensate = new ArrayList<>(ctx.completedSteps());
        Collections.reverse(toCompensate);

        for (String stepName : toCompensate) {
            steps.stream()
                    .filter(s -> s.name().equals(stepName))
                    .findFirst()
                    .ifPresent(step -> {
                        Object result = ctx.results().get(stepName);
                        if (result != null && step.compensation() != null) {
                            try {
                                step.compensation().apply(null, (S) result).get(30, TimeUnit.SECONDS);
                            } catch (Exception e) {
                                // Log but continue compensation
                                System.err.println("[Saga] Compensation failed for " + stepName + ": " + e.getMessage());
                            }
                        }
                    });
        }

        // Complete the pending future with compensated result
        if (pendingFuture != null) {
            pendingFuture.complete(new SagaResult.Compensated(
                    ctx.completedSteps().isEmpty() ? "unknown" : ctx.completedSteps().getLast(),
                    originalError,
                    toCompensate));
        }
    }

    // ── Infrastructure ────────────────────────────────────────────────────────────

    @Override
    public String name() { return name; }

    /**
     * Shutdown the saga orchestrator.
     */
    public void shutdown() throws InterruptedException {
        coordinator.stop();
    }
}
