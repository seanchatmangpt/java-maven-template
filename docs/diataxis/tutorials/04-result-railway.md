# Tutorial 4 — Railway Error Handling with Result<T,E>

> **Learning goal:** replace exception-driven control flow with a composable `Result<T,E>` pipeline that makes every failure case explicit, chainable, and testable.

---

## Prerequisites

- Familiarity with Java generics and lambdas
- Completed [Tutorial 1 — Hello, Proc](01-hello-proc.md) (for project setup)
- No prior knowledge of functional programming is required

---

## What you will build

A **user-registration pipeline** that:

1. Parses raw form input into a typed `RegistrationRequest` record
2. Validates each field (username length, email format, age range)
3. Calls a (simulated) repository to persist the user
4. Returns either a `RegisteredUser` or a structured `RegistrationError`

At no point does the pipeline throw an exception for expected failure cases. Every error is a first-class value that flows through the pipeline alongside successes.

---

## Background: what is railway-oriented programming?

Imagine a railway track that forks at every validation step:

```
parse ──► validate username ──► validate email ──► validate age ──► save ──► RegisteredUser
              │                      │                  │              │
         failure                failure            failure         failure
              │                      │                  │              │
              └──────────────────────┴──────────────────┴──────────────┴──► RegistrationError
```

On the **success track**, each step receives the previous result and computes the next.
On the **failure track**, computation stops — later steps are skipped automatically.

`Result<T,E>` is the type that represents *"either a `T` (success) or an `E` (failure)"*. Its methods (`map`, `flatMap`, `recover`, `fold`) let you chain operations on the success track while short-circuiting on failure — no `try/catch`, no null checks.

---

## Step 1 — Define the domain types

Create three files for the domain model.

**`src/main/java/org/acme/tutorial/RegistrationRequest.java`**

```java
package org.acme.tutorial;

/**
 * Raw input from a registration form.
 *
 * <p>All fields are Strings because form data arrives as text; validation converts them.
 */
public record RegistrationRequest(
        String username,
        String email,
        String ageText   // "25", "not-a-number", etc.
) {}
```

**`src/main/java/org/acme/tutorial/RegisteredUser.java`**

```java
package org.acme.tutorial;

/**
 * A successfully registered user.
 *
 * <p>This is the happy-path output of the pipeline.
 */
public record RegisteredUser(
        long id,
        String username,
        String email,
        int age
) {}
```

**`src/main/java/org/acme/tutorial/RegistrationError.java`**

```java
package org.acme.tutorial;

/**
 * All the ways registration can fail.
 *
 * <p>Sealed so that callers handle every case at compile time.
 */
public sealed interface RegistrationError
        permits RegistrationError.UsernameTooShort,
                RegistrationError.UsernameTooLong,
                RegistrationError.InvalidEmail,
                RegistrationError.AgeBelowMinimum,
                RegistrationError.AgeAboveMaximum,
                RegistrationError.AgeNotANumber,
                RegistrationError.UsernameTaken,
                RegistrationError.DatabaseUnavailable {

    record UsernameTooShort(int minLength, int actual)   implements RegistrationError {}
    record UsernameTooLong(int maxLength, int actual)    implements RegistrationError {}
    record InvalidEmail(String email)                    implements RegistrationError {}
    record AgeBelowMinimum(int minimum, int actual)      implements RegistrationError {}
    record AgeAboveMaximum(int maximum, int actual)      implements RegistrationError {}
    record AgeNotANumber(String raw)                     implements RegistrationError {}
    record UsernameTaken(String username)                implements RegistrationError {}
    record DatabaseUnavailable(String reason)            implements RegistrationError {}
}
```

Notice that every error carries the information needed to produce a useful message. There is no generic `"validation failed"` string — each variant is precise and structured.

---

## Step 2 — Build individual validation steps

Create `src/main/java/org/acme/tutorial/RegistrationValidation.java`. Each method returns a `Result<ValidatedInput, RegistrationError>`:

```java
package org.acme.tutorial;

import org.acme.Result;
import org.acme.tutorial.RegistrationError.AgeAboveMaximum;
import org.acme.tutorial.RegistrationError.AgeBelowMinimum;
import org.acme.tutorial.RegistrationError.AgeNotANumber;
import org.acme.tutorial.RegistrationError.InvalidEmail;
import org.acme.tutorial.RegistrationError.UsernameTooLong;
import org.acme.tutorial.RegistrationError.UsernameTooShort;

/**
 * Pure validation functions for registration fields.
 *
 * <p>Every method is a static, side-effect-free function from input to Result.
 */
public final class RegistrationValidation {

    private static final int USERNAME_MIN = 3;
    private static final int USERNAME_MAX = 30;
    private static final int AGE_MIN      = 13;
    private static final int AGE_MAX      = 120;

    private RegistrationValidation() {}

    public static Result<String, RegistrationError> validateUsername(String username) {
        if (username.length() < USERNAME_MIN) {
            return Result.failure(new UsernameTooShort(USERNAME_MIN, username.length()));
        }
        if (username.length() > USERNAME_MAX) {
            return Result.failure(new UsernameTooLong(USERNAME_MAX, username.length()));
        }
        return Result.success(username.trim().toLowerCase());
    }

    public static Result<String, RegistrationError> validateEmail(String email) {
        // Simple format check — production code would use a proper library
        boolean valid = email.contains("@")
                && email.contains(".")
                && !email.startsWith("@")
                && !email.endsWith(".");

        return valid
                ? Result.success(email.trim().toLowerCase())
                : Result.failure(new InvalidEmail(email));
    }

    public static Result<Integer, RegistrationError> validateAge(String ageText) {
        int age;
        try {
            age = Integer.parseInt(ageText.trim());
        } catch (NumberFormatException e) {
            return Result.failure(new AgeNotANumber(ageText));
        }

        if (age < AGE_MIN) {
            return Result.failure(new AgeBelowMinimum(AGE_MIN, age));
        }
        if (age > AGE_MAX) {
            return Result.failure(new AgeAboveMaximum(AGE_MAX, age));
        }
        return Result.success(age);
    }
}
```

Each function is a pure transformation. You can test them in complete isolation — no mocking, no wiring.

---

## Step 3 — Compose into a pipeline

Create `src/main/java/org/acme/tutorial/RegistrationService.java`. Here the individual validation steps are chained using `flatMap`:

```java
package org.acme.tutorial;

import java.util.concurrent.atomic.AtomicLong;
import org.acme.Result;
import org.acme.tutorial.RegistrationError.DatabaseUnavailable;
import org.acme.tutorial.RegistrationError.UsernameTaken;

/**
 * Composes validation steps and persistence into a single railway pipeline.
 */
public final class RegistrationService {

    // Simulated in-memory state — in production this would be a real repository
    private final java.util.Set<String> takenUsernames = new java.util.HashSet<>();
    private final AtomicLong idSequence = new AtomicLong(1);
    private boolean databaseHealthy = true;

    /**
     * Register a new user.
     *
     * <p>Returns {@code Result.success(user)} if all steps pass,
     * or {@code Result.failure(error)} at the first failing step.
     */
    public Result<RegisteredUser, RegistrationError> register(RegistrationRequest request) {
        return RegistrationValidation.validateUsername(request.username())
                .flatMap(username -> checkUsernameAvailability(username))
                .flatMap(username ->
                    RegistrationValidation.validateEmail(request.email())
                        .map(email -> new Object[]{ username, email })   // combine username + email
                )
                .flatMap(pair ->
                    RegistrationValidation.validateAge(request.ageText())
                        .map(age -> new Object[]{ pair[0], pair[1], age })
                )
                .flatMap(triple -> persist(
                        (String)  triple[0],
                        (String)  triple[1],
                        (Integer) triple[2]
                ));
    }

    // ---------------------------------------------------------------------------
    // Internal pipeline steps
    // ---------------------------------------------------------------------------

    private Result<String, RegistrationError> checkUsernameAvailability(String username) {
        if (takenUsernames.contains(username)) {
            return Result.failure(new UsernameTaken(username));
        }
        return Result.success(username);
    }

    private Result<RegisteredUser, RegistrationError> persist(
            String username, String email, int age) {
        if (!databaseHealthy) {
            return Result.failure(new DatabaseUnavailable("Connection refused"));
        }
        takenUsernames.add(username);
        long id = idSequence.getAndIncrement();
        return Result.success(new RegisteredUser(id, username, email, age));
    }

    // Test helper — simulate a database outage
    public void simulateDatabaseOutage() {
        databaseHealthy = false;
    }

    public void simulateDatabaseRecovery() {
        databaseHealthy = true;
    }
}
```

---

## Step 4 — Consume the Result with fold

Create `src/main/java/org/acme/tutorial/RegistrationResponse.java` — a view-layer record that converts a `Result` into a user-facing message:

```java
package org.acme.tutorial;

import org.acme.Result;
import org.acme.tutorial.RegistrationError.AgeAboveMaximum;
import org.acme.tutorial.RegistrationError.AgeBelowMinimum;
import org.acme.tutorial.RegistrationError.AgeNotANumber;
import org.acme.tutorial.RegistrationError.DatabaseUnavailable;
import org.acme.tutorial.RegistrationError.InvalidEmail;
import org.acme.tutorial.RegistrationError.UsernameTaken;
import org.acme.tutorial.RegistrationError.UsernameTooLong;
import org.acme.tutorial.RegistrationError.UsernameTooShort;

/**
 * Converts a pipeline Result into a human-readable response message.
 *
 * <p>{@code fold} collapses the two tracks (success / failure) into a single String.
 */
public final class RegistrationResponse {

    private RegistrationResponse() {}

    public static String from(Result<RegisteredUser, RegistrationError> result) {
        return result.fold(
                // Success track
                user -> "Welcome, %s! Your account (id=%d) is ready."
                            .formatted(user.username(), user.id()),

                // Failure track — pattern match over every error variant
                error -> switch (error) {
                    case UsernameTooShort(var min, var actual) ->
                            "Username too short: minimum %d characters, got %d.".formatted(min, actual);

                    case UsernameTooLong(var max, var actual) ->
                            "Username too long: maximum %d characters, got %d.".formatted(max, actual);

                    case InvalidEmail(var email) ->
                            "'%s' is not a valid email address.".formatted(email);

                    case AgeBelowMinimum(var min, var actual) ->
                            "You must be at least %d years old (got %d).".formatted(min, actual);

                    case AgeAboveMaximum(var max, var actual) ->
                            "Age %d exceeds the maximum of %d.".formatted(actual, max);

                    case AgeNotANumber(var raw) ->
                            "'%s' is not a valid age.".formatted(raw);

                    case UsernameTaken(var username) ->
                            "The username '%s' is already taken.".formatted(username);

                    case DatabaseUnavailable(var reason) ->
                            "Service temporarily unavailable, please try again. (%s)".formatted(reason);
                }
        );
    }
}
```

The `switch` over the sealed `RegistrationError` is **exhaustive** — the compiler enforces that every variant is handled. If you add a new error subtype later, the compiler will flag every `switch` that needs updating.

---

## Step 5 — Write the tests

Create `src/test/java/org/acme/tutorial/RegistrationServiceTest.java`:

```java
package org.acme.tutorial;

import org.acme.Result;
import org.assertj.core.api.WithAssertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RegistrationServiceTest implements WithAssertions {

    private RegistrationService service;

    @BeforeEach
    void setUp() {
        service = new RegistrationService();
    }

    // --- Happy path ---

    @Test
    void successfulRegistration() {
        var request = new RegistrationRequest("alice", "alice@example.com", "28");
        var result  = service.register(request);

        assertThat(result.isSuccess()).isTrue();
        result.fold(
            user -> {
                assertThat(user.username()).isEqualTo("alice");
                assertThat(user.email()).isEqualTo("alice@example.com");
                assertThat(user.age()).isEqualTo(28);
                return null;
            },
            err -> { fail("Expected success, got: " + err); return null; }
        );
    }

    @Test
    void responseMessageForSuccess() {
        var result   = service.register(new RegistrationRequest("bob", "bob@test.org", "35"));
        var response = RegistrationResponse.from(result);

        assertThat(response).startsWith("Welcome, bob!");
    }

    // --- Validation failures ---

    @Test
    void shortUsernameFailsValidation() {
        var result = service.register(new RegistrationRequest("ab", "ab@test.com", "20"));

        assertThat(result.isFailure()).isTrue();
        assertThat(result.failure()).isPresent()
                .get().isInstanceOf(RegistrationError.UsernameTooShort.class);
    }

    @Test
    void invalidEmailFailsValidation() {
        var result = service.register(new RegistrationRequest("charlie", "not-an-email", "25"));

        assertThat(result.isFailure()).isTrue();
        assertThat(RegistrationResponse.from(result))
                .contains("not a valid email");
    }

    @Test
    void ageNotANumberFailsValidation() {
        var result = service.register(new RegistrationRequest("dave", "dave@x.com", "twenty"));

        assertThat(RegistrationResponse.from(result))
                .contains("not a valid age");
    }

    @Test
    void ageBelowMinimumFailsValidation() {
        var result = service.register(new RegistrationRequest("eve", "eve@x.com", "10"));

        assertThat(RegistrationResponse.from(result))
                .contains("must be at least 13");
    }

    @Test
    void duplicateUsernameFailsAfterFirstRegistration() {
        service.register(new RegistrationRequest("frank", "frank@x.com", "30"));
        var second = service.register(new RegistrationRequest("frank", "frank2@x.com", "30"));

        assertThat(second.isFailure()).isTrue();
        assertThat(RegistrationResponse.from(second))
                .contains("already taken");
    }

    @Test
    void databaseOutageReturnsServiceUnavailable() {
        service.simulateDatabaseOutage();
        var result = service.register(new RegistrationRequest("grace", "grace@x.com", "22"));

        assertThat(RegistrationResponse.from(result))
                .contains("temporarily unavailable");
    }

    // --- Pipeline short-circuits at first failure ---

    @Test
    void pipelineStopsAtFirstFailure() {
        // Username is too short AND email is invalid AND age is wrong.
        // Only the first error (username) should be reported.
        var result = service.register(new RegistrationRequest("xy", "bad-email", "abc"));

        assertThat(result.failure()).isPresent()
                .get().isInstanceOf(RegistrationError.UsernameTooShort.class);
    }
}
```

Run:

```bash
mvnd test -Dtest=RegistrationServiceTest
```

---

## Step 6 — Use Result.of to wrap throwing code

Sometimes you need to call existing code that throws exceptions (a third-party library, a legacy method). `Result.of` converts a throwing `Supplier` into a `Result`:

```java
// Instead of:
try {
    int n = Integer.parseInt(input);
    return Result.success(n);
} catch (NumberFormatException e) {
    return Result.failure("not a number");
}

// Write:
Result<Integer, Throwable> r = Result.of(() -> Integer.parseInt(input));

// Then map the Throwable to your error type:
Result<Integer, RegistrationError> typed = r
    .mapFailure(ex -> new RegistrationError.AgeNotANumber(input));
```

This is useful at the boundary between exception-based code (most Java libraries) and your railway pipeline.

---

## What just happened?

You built a multi-step validation pipeline where:

- **Every step is a pure function** — easy to unit-test in isolation with no mocking.
- **Errors are typed values** — the sealed `RegistrationError` hierarchy means the compiler checks every consumer handles every case.
- **`flatMap` chains steps** — if any step returns `failure`, subsequent steps are skipped automatically. No `if (result.isSuccess())` guards needed.
- **`fold` collapses at the edge** — only at the final boundary (the HTTP response, the log message) do you need to handle both tracks at once.

The pipeline itself never throws. Exceptions are reserved for truly *unexpected* failures (e.g., a bug in your code, an out-of-memory error) — not for business-rule violations.

---

## Next steps

- **Tutorial 5** — [Scaffold a CRUD service with jgen](05-jgen-generate.md): generate an entire service — including `Result`-based error handling — from a single command.
- Explore `Result.recover` to provide a fallback value on failure: `result.recover(err -> defaultUser)`.
- Combine with `Parallel.all(...)` to validate multiple fields concurrently and collect **all** errors (not just the first) using a `List<RegistrationError>` as the failure type.
- Look at the `org.acme.dogfood.ResultRailway` source for production-grade examples used in the project's own dogfood suite.
