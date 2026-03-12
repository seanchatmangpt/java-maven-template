package io.github.seanchatmangpt.jotp.messaging.system;

import io.github.seanchatmangpt.jotp.Proc;
import io.github.seanchatmangpt.jotp.ProcessRegistry;
import io.github.seanchatmangpt.jotp.Result;
import io.github.seanchatmangpt.jotp.StateMachine;
import io.github.seanchatmangpt.jotp.StateMachine.Transition;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;

/**
 * Multi-step workflow orchestrator using StateMachine and ProcessRegistry.
 *
 * <p>Vernon's "Process Manager" pattern: coordinates work across multiple processes using named
 * process registrations and state machine transitions. Manages step ordering, error recovery, and
 * rollback semantics.
 *
 * <p>Mapping to Enterprise Integration Patterns:
 *
 * <ul>
 *   <li>Named process steps → ProcessRegistry (OTP: register/2)
 *   <li>Workflow state transitions → StateMachine<State, Event, WorkflowData>
 *   <li>Step handlers → BiFunction<WorkflowContext, StepInput, StepOutput>
 *   <li>Rollback chain → linked compensation steps
 * </ul>
 *
 * <p>Example:
 * <pre>{@code
 * var pm = new ProcessManager<OrderContext>("order-workflow")
 *     .step("validate", ctx -> input -> validate((Order) input), (ctx, out) -> null)
 *     .step("reserve", ctx -> input -> reserveInventory((Order) input), (ctx, out) -> release((Order) out))
 *     .step("charge", ctx -> input -> chargeCard((Order) input), (ctx, out) -> refund((Order) out));
 *
 * var result = pm.start(order).join();
 * }</pre>
 *
 * @param <C> workflow context type (carries state across all steps)
 */
public final class ProcessManager<C> {

  /**
   * A workflow step: ordered execution with input/output transformation and optional rollback.
   *
   * @param name step identifier (globally unique within manager)
   * @param handler pure function: (context, input) -> output
   * @param rollbackHandler inverse operation: (context, output) -> void
   */
  public record Step<C>(
      String name, BiFunction<C, Object, Object> handler, BiFunction<C, Object, Void> rollbackHandler) {}

  /**
   * Workflow execution context: carries step outputs and state across the entire workflow.
   */
  public static class WorkflowContext {
    private final Map<String, Object> stepOutputs = new ConcurrentHashMap<>();
    private final List<String> executedSteps = new ArrayList<>();
    private volatile Throwable lastError = null;

    /** Get the output of a previously executed step by name. */
    public Object getStepOutput(String stepName) {
      return stepOutputs.get(stepName);
    }

    /** Record that a step was successfully executed. */
    void recordStep(String stepName, Object output) {
      stepOutputs.put(stepName, output);
      executedSteps.add(stepName);
    }

    /** Retrieve the ordered list of executed steps. */
    public List<String> getExecutedSteps() {
      return List.copyOf(executedSteps);
    }

    /** Set error on last failed step. */
    void setError(Throwable error) {
      this.lastError = error;
    }

    /** Get the last error, if any. */
    public Optional<Throwable> getLastError() {
      return Optional.ofNullable(lastError);
    }

    /** Clear all step outputs and execution history (for reset). */
    void reset() {
      stepOutputs.clear();
      executedSteps.clear();
      lastError = null;
    }
  }

  /** Internal workflow state machine events. */
  sealed interface WorkflowEvent permits WorkflowEvent.StartWorkflow, WorkflowEvent.StepCompleted,
      WorkflowEvent.StepFailed, WorkflowEvent.RollbackStarted, WorkflowEvent.WorkflowDone {
    record StartWorkflow(Object initialInput) implements WorkflowEvent {}

    record StepCompleted(String stepName, Object output) implements WorkflowEvent {}

    record StepFailed(String stepName, Throwable error) implements WorkflowEvent {}

    record RollbackStarted(String fromStep) implements WorkflowEvent {}

    record WorkflowDone() implements WorkflowEvent {}
  }

  /** Internal workflow state machine states. */
  sealed interface WorkflowState permits WorkflowState.Idle, WorkflowState.Running,
      WorkflowState.RollingBack, WorkflowState.Failed, WorkflowState.Complete {
    record Idle() implements WorkflowState {}

    record Running(int currentStepIndex) implements WorkflowState {}

    record RollingBack(int fromStepIndex) implements WorkflowState {}

    record Failed(String reason) implements WorkflowState {}

    record Complete() implements WorkflowState {}
  }

  /** Workflow data carried across state transitions. */
  public static class WorkflowData {
    public final WorkflowContext context = new WorkflowContext();
    public int currentStepIndex = 0;
    public Object currentInput = null;
    public boolean rollbackInProgress = false;
  }

  private final String managerId;
  private final List<Step<?>> steps = new ArrayList<>();
  private StateMachine<WorkflowState, WorkflowEvent, WorkflowData> stateMachine;
  private Proc<WorkflowState, WorkflowEvent> stateMachineProc;
  private WorkflowData workflowData;

  /**
   * Create a new ProcessManager instance.
   *
   * @param managerId unique identifier for this manager (used in ProcessRegistry)
   */
  public ProcessManager(String managerId) {
    this.managerId = Objects.requireNonNull(managerId, "managerId must not be null");
  }

  /**
   * Add a step to the workflow in order.
   *
   * @param stepName unique name for this step (must not duplicate)
   * @param handler pure function processing the step
   * @param rollbackHandler inverse operation for recovery
   * @return this manager (for fluent API)
   */
  public ProcessManager<C> step(
      String stepName, BiFunction<C, Object, Object> handler, BiFunction<C, Object, Void> rollbackHandler) {
    for (Step<?> existing : steps) {
      if (existing.name.equals(stepName)) {
        throw new IllegalArgumentException("Step already defined: " + stepName);
      }
    }
    steps.add(new Step<>(stepName, handler, rollbackHandler));
    return this;
  }

  /**
   * Start the workflow with initial input, returning a CompletableFuture with the final result.
   *
   * <p>Creates a StateMachine that coordinates step execution. The state machine transitions
   * through Running -> Complete on success, or Running -> RollingBack -> Failed on error.
   *
   * @param initialInput input to the first step
   * @return a CompletableFuture<Result> indicating workflow completion or failure
   */
  public CompletableFuture<Result<WorkflowContext, String>> start(Object initialInput) {
    if (steps.isEmpty()) {
      return CompletableFuture.completedFuture(Result.failure("No steps defined"));
    }

    this.workflowData = new WorkflowData();
    this.workflowData.currentInput = initialInput;

    // Create state machine with transition function
    StateMachine.TransitionFn<WorkflowState, WorkflowEvent, WorkflowData> transitionFn =
        (state, event, data) -> {
          return switch (state) {
            case WorkflowState.Idle _ ->
                switch (event) {
                  case WorkflowEvent.StartWorkflow _ ->
                      Transition.nextState(new WorkflowState.Running(0), data);
                  default -> Transition.keepState(data);
                };

            case WorkflowState.Running running ->
                switch (event) {
                  case WorkflowEvent.StepCompleted completed -> {
                    data.context.recordStep(completed.stepName(), completed.output());
                    if (running.currentStepIndex() < steps.size() - 1) {
                      data.currentStepIndex = running.currentStepIndex() + 1;
                      data.currentInput = completed.output();
                      yield Transition.nextState(new WorkflowState.Running(data.currentStepIndex), data);
                    } else {
                      yield Transition.nextState(new WorkflowState.Complete(), data);
                    }
                  }
                  case WorkflowEvent.StepFailed failed -> {
                    data.context.setError(failed.error());
                    data.rollbackInProgress = true;
                    yield Transition.nextState(new WorkflowState.RollingBack(running.currentStepIndex()), data);
                  }
                  default -> Transition.keepState(data);
                };

            case WorkflowState.RollingBack rolling ->
                switch (event) {
                  case WorkflowEvent.RollbackStarted _ -> {
                    if (rolling.fromStepIndex() > 0) {
                      data.currentStepIndex = rolling.fromStepIndex() - 1;
                      yield Transition.nextState(new WorkflowState.RollingBack(data.currentStepIndex), data);
                    } else {
                      yield Transition.nextState(new WorkflowState.Failed("Rollback complete"), data);
                    }
                  }
                  case WorkflowEvent.WorkflowDone _ ->
                      Transition.nextState(new WorkflowState.Failed("Rollback complete"), data);
                  default -> Transition.keepState(data);
                };

            case WorkflowState.Failed _, WorkflowState.Complete _ -> Transition.keepState(data);
          };
        };

    this.stateMachine = new StateMachine<>(new WorkflowState.Idle(), workflowData, transitionFn);
    ProcessRegistry.register(managerId, (Proc<WorkflowState, WorkflowEvent>) (Object) stateMachine);

    // Send initial event
    stateMachine.send(new WorkflowEvent.StartWorkflow(initialInput));

    // Process steps asynchronously
    processNextStep(0);

    // Return future that completes when workflow finishes
    return CompletableFuture.supplyAsync(
        () -> {
          while (stateMachine.isRunning()
              && !(stateMachine.state() instanceof WorkflowState.Complete
                  || stateMachine.state() instanceof WorkflowState.Failed)) {
            try {
              Thread.sleep(10);
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
              return Result.failure("Workflow interrupted: " + e.getMessage());
            }
          }

          if (workflowData.context.getLastError().isPresent()) {
            return Result.failure("Workflow failed: " + workflowData.context.getLastError().get().getMessage());
          }
          return Result.success(workflowData.context);
        });
  }

  /** Internal: process the next step in the workflow. */
  private void processNextStep(int stepIndex) {
    if (stepIndex >= steps.size()) {
      stateMachine.send(new WorkflowEvent.WorkflowDone());
      return;
    }

    Step<?> step = steps.get(stepIndex);
    try {
      Object output = step.handler.apply((C) workflowData.context, workflowData.currentInput);
      stateMachine.send(new WorkflowEvent.StepCompleted(step.name, output));
      processNextStep(stepIndex + 1);
    } catch (Exception e) {
      workflowData.context.setError(e);
      stateMachine.send(new WorkflowEvent.StepFailed(step.name, e));
      rollback(stepIndex);
    }
  }

  /** Internal: execute rollback chain in reverse order. */
  private void rollback(int fromIndex) {
    stateMachine.send(new WorkflowEvent.RollbackStarted("step_" + fromIndex));
    for (int i = fromIndex; i >= 0; i--) {
      Step<?> step = steps.get(i);
      Object output = workflowData.context.getStepOutput(step.name);
      try {
        if (output != null) {
          step.rollbackHandler.apply((C) workflowData.context, output);
        }
      } catch (Exception e) {
        System.err.println("Rollback failed for step " + step.name + ": " + e.getMessage());
      }
    }
  }

  /** Get the current workflow state (for testing/monitoring). */
  public Optional<WorkflowState> currentState() {
    return stateMachine != null ? Optional.of(stateMachine.state()) : Optional.empty();
  }

  /** Stop the workflow manager. */
  public void stop() throws InterruptedException {
    if (stateMachine != null) {
      stateMachine.stop();
      ProcessRegistry.unregister(managerId);
    }
  }

  /** Get the workflow data for introspection. */
  public Optional<WorkflowData> workflowData() {
    return Optional.ofNullable(workflowData);
  }

  /** Get step count. */
  public int stepCount() {
    return steps.size();
  }

  /** Get a specific step by index. */
  public Optional<Step<?>> getStep(int index) {
    return index >= 0 && index < steps.size() ? Optional.of(steps.get(index)) : Optional.empty();
  }

  /** Next: allow retrieval of step by name for testing. */
  public Optional<Object> next() {
    if (workflowData != null && workflowData.currentStepIndex < steps.size()) {
      return Optional.of(steps.get(workflowData.currentStepIndex));
    }
    return Optional.empty();
  }
}
