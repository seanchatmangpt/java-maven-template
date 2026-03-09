# Tutorial 3 — Implement a Traffic-Light StateMachine

> **Learning goal:** model a domain workflow with `StateMachine<S,E,D>` using sealed transition types, and trigger timed state changes with `ProcTimer`.

---

## Prerequisites

- Completed [Tutorial 1 — Hello, Proc](01-hello-proc.md)
- You are comfortable with sealed interfaces and pattern-matching `switch`
- (Optional) [Tutorial 2](02-supervised-service.md) for supervision context

---

## What you will build

A **traffic-light controller** that:

1. Cycles through `RED → GREEN → YELLOW → RED` states
2. Transitions on domain events (`CarArrived`, `TimerExpired`)
3. Stores auxiliary data (how long each phase has been active) in a typed `LightData` record
4. Uses `ProcTimer` to schedule automatic `TimerExpired` events

By the end of this tutorial you will be able to model any finite-state workflow — order processing, connection lifecycle, user onboarding — using the same pattern.

---

## Background: gen_statem in Java

Erlang's `gen_statem` behaviour models a process as a set of **states**, each with its own set of **event handlers**. The Java `StateMachine<S,E,D>` port follows the same separation:

| Type parameter | Erlang equivalent | Role |
|---|---|---|
| `S` | state name (atom) | Which state are we in? |
| `E` | event type | What happened? |
| `D` | state data | Auxiliary data that travels with the machine |

A handler returns a sealed `Transition<S,D>`:

| Subtype | Meaning |
|---|---|
| `Transition.Stay<S,D>` | Remain in the current state (optionally update data) |
| `Transition.MoveTo<S,D>` | Move to a new state (optionally update data) |
| `Transition.Stop<S,D>` | Terminate the state machine |

Because `Transition` is sealed, the compiler verifies that every handler returns a valid transition and that every event is handled.

---

## Step 1 — Define the state, event, and data types

Create three files. Keep them small and focused — Java records shine here.

**`src/main/java/org/acme/tutorial/TrafficLight.java`**

```java
package org.acme.tutorial;

/** The three possible states of the traffic light. */
public enum TrafficLight {
    RED,
    GREEN,
    YELLOW
}
```

**`src/main/java/org/acme/tutorial/TrafficEvent.java`**

```java
package org.acme.tutorial;

/**
 * Events that drive the traffic-light state machine.
 *
 * <p>Sealed so that the switch in the handler is exhaustive.
 */
public sealed interface TrafficEvent
        permits TrafficEvent.CarArrived, TrafficEvent.TimerExpired, TrafficEvent.EmergencyOverride {

    /** A vehicle has arrived at the intersection. */
    record CarArrived(String vehicleId) implements TrafficEvent {}

    /** The phase timer has fired — time to move to the next colour. */
    record TimerExpired() implements TrafficEvent {}

    /** Emergency vehicles need immediate green. */
    record EmergencyOverride() implements TrafficEvent {}
}
```

**`src/main/java/org/acme/tutorial/LightData.java`**

```java
package org.acme.tutorial;

import java.time.Instant;

/**
 * Auxiliary data carried alongside the state machine state.
 *
 * <p>Records are immutable — the state machine returns a new instance on each transition.
 */
public record LightData(
        Instant phaseStarted,   // when the current colour phase began
        int carsServed,         // total cars that passed through on GREEN
        int cycleCount          // how many full RED→GREEN→YELLOW→RED cycles completed
) {
    /** Convenience factory for a fresh data object. */
    public static LightData initial() {
        return new LightData(Instant.now(), 0, 0);
    }

    /** Return a copy with phaseStarted reset to now. */
    public LightData newPhase() {
        return new LightData(Instant.now(), carsServed, cycleCount);
    }

    /** Return a copy recording that one more car was served. */
    public LightData carServed() {
        return new LightData(phaseStarted, carsServed + 1, cycleCount);
    }

    /** Return a copy after completing a full cycle. */
    public LightData cycleCompleted() {
        return new LightData(Instant.now(), carsServed, cycleCount + 1);
    }
}
```

---

## Step 2 — Build the state machine

Create `src/main/java/org/acme/tutorial/TrafficLightController.java`. This is where the interesting logic lives.

```java
package org.acme.tutorial;

import org.acme.StateMachine;
import org.acme.StateMachine.Transition;
import org.acme.tutorial.TrafficEvent.CarArrived;
import org.acme.tutorial.TrafficEvent.EmergencyOverride;
import org.acme.tutorial.TrafficEvent.TimerExpired;

/**
 * Traffic-light controller modelled as a StateMachine.
 *
 * <p>Transition logic is pure: each handler is a static method with no side effects.
 */
public final class TrafficLightController {

    private TrafficLightController() {}

    /** Build and return a configured state machine starting at RED. */
    public static StateMachine<TrafficLight, TrafficEvent, LightData> create() {
        return StateMachine.<TrafficLight, TrafficEvent, LightData>builder()
                .initialState(TrafficLight.RED)
                .initialData(LightData.initial())

                // --- RED state ---
                .state(TrafficLight.RED)
                    .on(CarArrived.class,         TrafficLightController::redOnCarArrived)
                    .on(TimerExpired.class,        TrafficLightController::redOnTimerExpired)
                    .on(EmergencyOverride.class,   TrafficLightController::anyOnEmergency)
                .end()

                // --- GREEN state ---
                .state(TrafficLight.GREEN)
                    .on(CarArrived.class,          TrafficLightController::greenOnCarArrived)
                    .on(TimerExpired.class,         TrafficLightController::greenOnTimerExpired)
                    .on(EmergencyOverride.class,    TrafficLightController::anyOnEmergency)
                .end()

                // --- YELLOW state ---
                .state(TrafficLight.YELLOW)
                    .on(CarArrived.class,           TrafficLightController::yellowOnCarArrived)
                    .on(TimerExpired.class,          TrafficLightController::yellowOnTimerExpired)
                    .on(EmergencyOverride.class,     TrafficLightController::anyOnEmergency)
                .end()

                .build();
    }

    // -------------------------------------------------------------------------
    // RED handlers
    // -------------------------------------------------------------------------

    private static Transition<TrafficLight, LightData> redOnCarArrived(
            TrafficLight state, CarArrived event, LightData data) {
        // Car arrives at red — note it but stay red (timer will fire later)
        System.out.printf("[RED] Car %s is waiting.%n", event.vehicleId());
        return new Transition.Stay<>(data);
    }

    private static Transition<TrafficLight, LightData> redOnTimerExpired(
            TrafficLight state, TimerExpired event, LightData data) {
        // Timer fires — go green
        System.out.println("[RED] Timer expired → moving to GREEN");
        return new Transition.MoveTo<>(TrafficLight.GREEN, data.newPhase());
    }

    // -------------------------------------------------------------------------
    // GREEN handlers
    // -------------------------------------------------------------------------

    private static Transition<TrafficLight, LightData> greenOnCarArrived(
            TrafficLight state, CarArrived event, LightData data) {
        // Car passes through on green
        System.out.printf("[GREEN] Car %s passed through.%n", event.vehicleId());
        return new Transition.Stay<>(data.carServed());
    }

    private static Transition<TrafficLight, LightData> greenOnTimerExpired(
            TrafficLight state, TimerExpired event, LightData data) {
        // Timer fires — warn with yellow before going red
        System.out.println("[GREEN] Timer expired → moving to YELLOW");
        return new Transition.MoveTo<>(TrafficLight.YELLOW, data.newPhase());
    }

    // -------------------------------------------------------------------------
    // YELLOW handlers
    // -------------------------------------------------------------------------

    private static Transition<TrafficLight, LightData> yellowOnCarArrived(
            TrafficLight state, CarArrived event, LightData data) {
        // Car arrives during yellow — just log it
        System.out.printf("[YELLOW] Car %s approaching, slowing down.%n", event.vehicleId());
        return new Transition.Stay<>(data);
    }

    private static Transition<TrafficLight, LightData> yellowOnTimerExpired(
            TrafficLight state, TimerExpired event, LightData data) {
        // Timer fires — back to red, complete the cycle
        System.out.println("[YELLOW] Timer expired → moving to RED");
        return new Transition.MoveTo<>(TrafficLight.RED, data.cycleCompleted().newPhase());
    }

    // -------------------------------------------------------------------------
    // Shared handlers
    // -------------------------------------------------------------------------

    private static Transition<TrafficLight, LightData> anyOnEmergency(
            TrafficLight state, EmergencyOverride event, LightData data) {
        // Emergency: go green immediately regardless of current state
        System.out.printf("[%s] Emergency override → immediate GREEN%n", state);
        return new Transition.MoveTo<>(TrafficLight.GREEN, data.newPhase());
    }
}
```

---

## Step 3 — Write tests for state transitions

Create `src/test/java/org/acme/tutorial/TrafficLightControllerTest.java`:

```java
package org.acme.tutorial;

import org.acme.StateMachine;
import org.acme.tutorial.TrafficEvent.CarArrived;
import org.acme.tutorial.TrafficEvent.EmergencyOverride;
import org.acme.tutorial.TrafficEvent.TimerExpired;
import org.assertj.core.api.WithAssertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TrafficLightControllerTest implements WithAssertions {

    private StateMachine<TrafficLight, TrafficEvent, LightData> sm;

    @BeforeEach
    void setUp() {
        sm = TrafficLightController.create();
    }

    @Test
    void initialStateIsRed() {
        assertThat(sm.currentState()).isEqualTo(TrafficLight.RED);
    }

    @Test
    void timerExpiredMovesRedToGreen() {
        sm.send(new TimerExpired());

        assertThat(sm.currentState()).isEqualTo(TrafficLight.GREEN);
    }

    @Test
    void fullCycleRedGreenYellowRed() {
        sm.send(new TimerExpired());   // RED → GREEN
        sm.send(new TimerExpired());   // GREEN → YELLOW
        sm.send(new TimerExpired());   // YELLOW → RED

        assertThat(sm.currentState()).isEqualTo(TrafficLight.RED);
        assertThat(sm.currentData().cycleCount()).isEqualTo(1);
    }

    @Test
    void carPassingOnGreenIncrementsCounter() {
        sm.send(new TimerExpired());                          // → GREEN
        sm.send(new CarArrived("TRUCK-001"));
        sm.send(new CarArrived("SEDAN-042"));
        sm.send(new CarArrived("BUS-007"));

        assertThat(sm.currentData().carsServed()).isEqualTo(3);
    }

    @Test
    void carArrivingOnRedDoesNotChangeState() {
        sm.send(new CarArrived("CAR-999"));  // still RED

        assertThat(sm.currentState()).isEqualTo(TrafficLight.RED);
    }

    @Test
    void emergencyOverrideFromRedGoesDirectlyToGreen() {
        // Start at RED, emergency should jump straight to GREEN (skip YELLOW)
        sm.send(new EmergencyOverride());

        assertThat(sm.currentState()).isEqualTo(TrafficLight.GREEN);
    }

    @Test
    void emergencyOverrideFromYellowGoesDirectlyToGreen() {
        sm.send(new TimerExpired());   // → GREEN
        sm.send(new TimerExpired());   // → YELLOW
        sm.send(new EmergencyOverride());

        assertThat(sm.currentState()).isEqualTo(TrafficLight.GREEN);
    }

    @Test
    void twoCyclesTrackCycleCount() {
        for (int i = 0; i < 2; i++) {
            sm.send(new TimerExpired());   // RED → GREEN
            sm.send(new TimerExpired());   // GREEN → YELLOW
            sm.send(new TimerExpired());   // YELLOW → RED
        }

        assertThat(sm.currentData().cycleCount()).isEqualTo(2);
    }
}
```

Run:

```bash
mvnd test -Dtest=TrafficLightControllerTest
```

---

## Step 4 — Add timed transitions with ProcTimer

Real traffic lights do not wait for external timer messages — they drive themselves. Integrate `ProcTimer` to fire `TimerExpired` automatically.

Create `src/main/java/org/acme/tutorial/SelfTimedTrafficLight.java`:

```java
package org.acme.tutorial;

import java.time.Duration;
import org.acme.Proc;
import org.acme.ProcRef;
import org.acme.ProcTimer;
import org.acme.StateMachine;
import org.acme.tutorial.TrafficEvent.TimerExpired;

/**
 * A traffic light that drives itself using ProcTimer.
 *
 * <p>Each time the StateMachine transitions, the Proc wrapping it schedules
 * the next TimerExpired event automatically.
 */
public final class SelfTimedTrafficLight {

    // Phase durations (short for testing)
    private static final Duration RED_DURATION    = Duration.ofSeconds(2);
    private static final Duration GREEN_DURATION  = Duration.ofSeconds(3);
    private static final Duration YELLOW_DURATION = Duration.ofSeconds(1);

    private final ProcRef<StateMachine<TrafficLight, TrafficEvent, LightData>, TrafficEvent> ref;

    public SelfTimedTrafficLight() {
        var sm = TrafficLightController.create();

        // The Proc wraps the StateMachine as its state.
        // On each message, we feed the event into the machine and schedule the next timer.
        ref = Proc.of(sm, (machine, event) -> {
            machine.send(event);
            scheduleNextTimer(ref(), machine.currentState());
            return machine;
        });

        // Kick off the first timer
        scheduleNextTimer(ref, TrafficLight.RED);
    }

    public TrafficLight currentState() {
        // ask() with a no-op message to read current state
        try {
            return ref.ask(null).get().currentState();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public void send(TrafficEvent event) {
        ref.tell(event);
    }

    public void stop() {
        ref.stop();
    }

    private ProcRef<StateMachine<TrafficLight, TrafficEvent, LightData>, TrafficEvent> ref() {
        return ref;
    }

    private static void scheduleNextTimer(
            ProcRef<StateMachine<TrafficLight, TrafficEvent, LightData>, TrafficEvent> procRef,
            TrafficLight state) {
        Duration delay = switch (state) {
            case RED    -> RED_DURATION;
            case GREEN  -> GREEN_DURATION;
            case YELLOW -> YELLOW_DURATION;
        };
        ProcTimer.sendAfter(delay, procRef, new TimerExpired());
    }
}
```

---

## Step 5 — Test the self-timed light

Add `src/test/java/org/acme/tutorial/SelfTimedTrafficLightTest.java`:

```java
package org.acme.tutorial;

import static org.awaitility.Awaitility.await;

import java.time.Duration;
import org.assertj.core.api.WithAssertions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SelfTimedTrafficLightTest implements WithAssertions {

    private SelfTimedTrafficLight light;

    @BeforeEach
    void setUp() {
        light = new SelfTimedTrafficLight();
    }

    @AfterEach
    void tearDown() {
        light.stop();
    }

    @Test
    void startsRed() {
        assertThat(light.currentState()).isEqualTo(TrafficLight.RED);
    }

    @Test
    void automaticallyTransitionsToGreenAfterRedTimer() {
        // The RED phase is 2 seconds; wait up to 3 seconds for the transition.
        await().atMost(Duration.ofSeconds(3))
               .untilAsserted(() ->
                   assertThat(light.currentState()).isEqualTo(TrafficLight.GREEN));
    }

    @Test
    void emergencyOverrideWorksWhileSelfTimed() {
        light.send(new TrafficEvent.EmergencyOverride());

        await().atMost(Duration.ofMillis(500))
               .untilAsserted(() ->
                   assertThat(light.currentState()).isEqualTo(TrafficLight.GREEN));
    }
}
```

Run:

```bash
mvnd test -Dtest=SelfTimedTrafficLightTest
```

These tests use **Awaitility** (already on the test classpath) to poll the state asynchronously without `Thread.sleep` — the `await().atMost(...)` call retries the assertion until it passes or times out.

---

## What just happened?

You built a finite-state machine with three layers:

1. **Domain types** — `TrafficLight` (state), `TrafficEvent` (events), `LightData` (data). All plain Java, no framework coupling.
2. **Handler logic** — pure static methods. Each returns a `Transition` (sealed), so the compiler guarantees every state+event combination is handled and that only valid transitions are expressed.
3. **Self-driving integration** — a `Proc` wrapped around the `StateMachine`, using `ProcTimer.sendAfter` to enqueue the next event automatically after each transition.

The key insight: **the state machine itself has no knowledge of time or threads**. It is a pure function from `(state, event, data)` to `Transition`. Timing and concurrency are concerns of the surrounding `Proc` wrapper — exactly the separation of concerns that makes OTP systems easy to reason about and test.

---

## Next steps

- **Tutorial 4** — [Railway error handling with Result<T,E>](04-result-railway.md): handle errors without exceptions in a data-pipeline style.
- Add entry/exit actions to transitions by logging inside the `Proc` handler when `machine.currentState()` changes.
- Model a more complex domain: an order lifecycle (`PENDING → PAYMENT_PENDING → CONFIRMED → SHIPPED → DELIVERED`) using the same pattern.
- Combine with Tutorial 2: put the `SelfTimedTrafficLight` under a `Supervisor` so it restarts from RED if something goes wrong.
