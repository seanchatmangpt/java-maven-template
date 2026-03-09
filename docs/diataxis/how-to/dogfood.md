# How to Run the Dogfood Verification Pipeline

## Problem

You have modified or added templates and need to confirm that they produce Java
code that compiles, formats correctly, and passes its tests.

## Solution

Run `bin/dogfood verify` (or the Maven equivalent `./mvnw verify -Ddogfood`).
This pipeline checks that all dogfood source files exist, compiles them, formats
them, and runs their tests.

## Prerequisites

- `mvnd` 2.0.0-rc-3 installed and on `PATH` (or use `./mvnw`)
- Java 26 (GraalVM Community CE 25.0.2 or later) — check with `java -version`
- If `https_proxy` is set, start the local proxy first (see Step 1)

---

## Step 1 — Start the local Maven proxy (if using a corporate proxy)

```bash
python3 maven-proxy-v2.py &
```

Skip this step if `https_proxy` is not set in your environment.

---

## Step 2 — Warm the build cache (optional, long sessions only)

```bash
mvnd compile -q -T1C
```

---

## Step 3 — Check all dogfood source files exist

```bash
bin/dogfood generate
```

Expected output:

```
[dogfood] Checking dogfood source files...
[dogfood] All dogfood source files present.
```

If a file is missing, the script prints the path and exits non-zero.

---

## Step 4 — Show template coverage

```bash
bin/dogfood report
```

Expected output lists each template category and the corresponding dogfood file:

```
core/         → src/main/java/org/acme/dogfood/Person.java              ✓
concurrency/  → src/main/java/org/acme/dogfood/VirtualThreadPatterns.java ✓
patterns/     → src/main/java/org/acme/dogfood/TextTransformStrategy.java ✓
...
```

---

## Step 5 — Run the full verification pipeline

```bash
bin/dogfood verify
```

This runs:
1. `generate` — checks all source files exist
2. `./mvnw compile` — compiles all dogfood sources
3. `./mvnw spotless:check` — verifies formatting
4. `./mvnw test` — runs unit tests (including dogfood tests)
5. `report` — prints coverage summary

Alternatively, use Maven directly:

```bash
./mvnw verify -Ddogfood
```

Or with Maven Daemon (faster):

```bash
bin/mvndw verify -Ddogfood
```

---

## Step 6 — Run dogfood tests in isolation

```bash
mvnd test -Dtest=ResultRailwayTest
mvnd test -Dtest=InputValidationTest
mvnd test -Dtest=PersonTest
mvnd test -Dtest=RefactorEngineTest
```

---

## Step 7 — Add a new dogfood file

1. Generate the file from its template:

   ```bash
   bin/jgen generate -t <category>/<template> -n <ClassName> -p org.acme.dogfood
   ```

2. Confirm it appears in `generate` output:

   ```bash
   bin/dogfood generate
   ```

3. Add a corresponding test class under `src/test/java/org/acme/dogfood/` if the
   template includes testable behaviour.

4. Run the full pipeline:

   ```bash
   bin/dogfood verify
   ```

---

## Verification

A passing pipeline ends with:

```
[INFO] BUILD SUCCESS
[dogfood] Coverage: 10/10 categories covered.
```

Exit code 0 means all checks passed.

---

## Troubleshooting

**`bin/dogfood: Permission denied`**
```bash
chmod +x bin/dogfood bin/jgen bin/mvndw
```

**Compilation fails with `--enable-preview` errors.**
Ensure the active JDK is Java 26:
```bash
java -version
# Should print: java version "26" ...
```
Set `JAVA_HOME` if needed:
```bash
export JAVA_HOME=/path/to/graalvm-26
```

**`spotless:check` fails.**
The PostToolUse hook should have applied formatting automatically after any
`.java` edit. Run it manually:
```bash
./mvnw spotless:apply
```
Then re-run `bin/dogfood verify`.

**A dogfood test fails in CI but passes locally.**
Parallel test execution (`junit-platform.properties`) can expose race conditions.
Check for shared mutable state in test classes and add `ProcessRegistry.reset()`
to `@BeforeEach` / `@AfterEach` where registry is used.
