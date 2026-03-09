# How to Refactor Legacy Java Code with jgen / RefactorEngine

## Problem

You have an existing Java codebase (Java 8–17 style) and need to migrate it to
Java 26 patterns: records, sealed types, virtual threads, pattern matching,
`Result<T,E>`, and OTP primitives.

## Solution

Run `bin/jgen refactor` to generate a scored migration plan and an executable
shell script, then apply it. Alternatively, call `RefactorEngine` from Java code
for programmatic access.

## Prerequisites

- `ggen-cli` installed: `cargo install ggen-cli --features paas,ai`
- `bin/jgen` is executable: `chmod +x bin/jgen`
- `mvnd` on `PATH`

---

## Step 1 — Detect legacy patterns (quick scan)

```bash
bin/jgen migrate --source ./legacy/src
```

Prints a grep-based report of legacy patterns found (e.g., raw `Optional` chains,
mutable POJOs, `Thread.new`, unchecked exceptions).

---

## Step 2 — Score the codebase

```bash
bin/jgen refactor --source ./legacy/src --score
```

Outputs a per-file modernisation score (0–100) and a ranked list of files by
refactoring ROI. Example:

```
File                        Score  Issues
legacy/UserService.java      23    mutable-pojo, raw-thread, checked-exception
legacy/OrderProcessor.java   41    synchronized-block, null-check-chain
legacy/Config.java           68    string-concat, for-loop-stream
```

---

## Step 3 — Generate the migration plan

```bash
bin/jgen refactor --source ./legacy/src --plan
```

This writes `migrate.sh` to the current directory. The script contains ordered
`bin/jgen generate` commands, one per detected migration opportunity.

---

## Step 4 — Review the plan before applying

```bash
cat migrate.sh
```

Each line is a `bin/jgen generate` invocation. Remove or comment out any lines
you do not want applied.

---

## Step 5 — Apply the migrations

```bash
bash migrate.sh
```

The script generates Java 26 files alongside (or replacing) legacy files, as
configured in each template.

---

## Step 6 — Compile and verify

```bash
mvnd verify
```

Fix any compilation errors reported. Common issues and fixes are covered in
[Troubleshooting](#troubleshooting).

---

## Step 7 — Use the RefactorEngine Java API

For programmatic or CI use:

```java
import org.acme.dogfood.innovation.RefactorEngine;
import java.nio.file.Files;
import java.nio.file.Path;

var plan = RefactorEngine.analyze(Path.of("./legacy/src"));

// Print human-readable summary
System.out.println(plan.summary());

// Write the migration script
Files.writeString(Path.of("migrate.sh"), plan.toScript());
```

Access per-file detail:

```java
for (var file : plan.files()) {
    System.out.printf("%-40s score=%d%n", file.path(), file.score());
    file.commands().forEach(cmd -> System.out.println("  " + cmd.toShellCommand()));
}
```

---

## Step 8 — Run specific template migrations manually

List available templates:

```bash
bin/jgen list
bin/jgen list --category patterns
```

Apply a single template:

```bash
bin/jgen generate -t core/record       -n Person       -p com.example.model
bin/jgen generate -t error-handling/result -n OrderResult  -p com.example.order
bin/jgen generate -t concurrency/virtual-threads -n TaskRunner -p com.example.async
```

---

## Step 9 — Validate results with dogfood

After applying migrations, run the dogfood pipeline to confirm generated code
compiles and tests pass:

```bash
bin/dogfood verify
```

---

## Verification

```bash
# Score improves after applying migrations
bin/jgen refactor --source ./legacy/src --score  # before: avg 35
bash migrate.sh
bin/jgen refactor --source ./legacy/src --score  # after:  avg 78

# Build passes
mvnd verify
```

---

## Troubleshooting

**`cargo install ggen-cli` fails.**
Ensure Rust toolchain is installed: `curl https://sh.rustup.rs -sSf | sh`.

**`bin/jgen: command not found`.**
```bash
chmod +x bin/jgen
# OR use the full path:
./bin/jgen refactor --source ./legacy/src --plan
```

**Generated code does not compile (`--enable-preview` errors).**
Confirm the active JDK is Java 26:
```bash
java -version
```

**`migrate.sh` overwrites files I wanted to keep.**
Review and edit `migrate.sh` before running `bash migrate.sh`. Back up the
`legacy/src` directory first:
```bash
cp -r legacy/src legacy/src.bak
```

**`RefactorEngine.analyze` returns an empty plan.**
Check that the source path contains `.java` files and is not a compiled
`target/` directory. Pass the source root, not the project root:
```java
RefactorEngine.analyze(Path.of("./legacy/src/main/java"));
```

**Score is unexpectedly low after migration.**
Some patterns require manual intervention (e.g., converting `synchronized`
blocks to virtual-thread-friendly alternatives). Review the `--score` output
for remaining issues and apply templates individually.
