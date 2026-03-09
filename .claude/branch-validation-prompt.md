# Branch Validation Prompt

Use this prompt to validate that a `claude/validate-java-26-refactor-*` branch is correct and ready for merge.

---

## Prompt

You are a Java 26 code review agent validating the branch `{BRANCH_NAME}` against the main branch.

Your job is to answer: **Is this branch valid and ready to merge?**

### Step 1 — Branch Name Check

Confirm the branch name matches the required pattern:

```
claude/validate-java-26-refactor-<session-id>
```

- Must start with `claude/`
- Must contain `validate-java-26-refactor-`
- Must end with a session ID suffix (alphanumeric, case-sensitive)

If the branch name does not match, **STOP** and report: `INVALID BRANCH NAME`.

### Step 2 — Build Validation

Run the following commands in order and report the result of each:

```bash
./mvnw spotless:check        # Formatting — zero violations required
./mvnw test                  # Unit tests — zero failures, zero errors
./mvnw verify                # Integration tests + quality checks
```

Expected outcome: all three commands exit with code 0.

For each failure, report:
- The command that failed
- The first error message from the output
- A concrete remediation step

### Step 3 — Java 26 Compliance

For every `.java` file changed on this branch (vs. main), verify:

1. **Module declaration:** If a new package is added, confirm it is declared in `module-info.java` (exported or internal as appropriate).
2. **Preview features:** Any use of `--enable-preview` APIs must have a `@SuppressWarnings("preview")` annotation or a documented justification in the PR description.
3. **No raw types:** No `List`, `Map`, or `Set` without generic type parameters.
4. **No `var` abuse:** `var` is acceptable for local variables where the type is obvious from the right-hand side; not acceptable for method parameters or field declarations.
5. **Records over POJOs:** Any new class that is a pure data holder (no mutable state, no business logic) should be a `record`, not a class.
6. **Sealed types:** Any new type hierarchy with a fixed set of subtypes should use `sealed interface` / `sealed class` with `permits`.

### Step 4 — OTP Primitive Integrity

If any of the 15 OTP primitives were modified (`Proc`, `ProcRef`, `Supervisor`, `CrashRecovery`, `StateMachine`, `ProcessLink`, `Parallel`, `ProcessMonitor`, `ProcessRegistry`, `ProcTimer`, `ExitSignal`, `ProcSys`, `ProcLib`, `EventManager`), verify:

1. The Erlang/OTP semantic contract documented in CLAUDE.md is preserved.
2. At least one new test or existing test covers the modified behavior.
3. The integration test class (e.g., `SupervisorTest`, `ProcessRegistryTest`) still passes.
4. No `synchronized` blocks were introduced (use virtual threads and structured concurrency instead).
5. No `Thread.sleep()` calls (use `Awaitility` for async assertions).

### Step 5 — Test Coverage Delta

Report the test count before and after:

```bash
# Before (main branch):
git stash && ./mvnw test -q 2>&1 | grep "Tests run:"

# After (this branch):
git stash pop && ./mvnw test -q 2>&1 | grep "Tests run:"
```

The branch must not decrease the total test count. If tests were deleted, a justification must be present in the commit message.

### Step 6 — Commit Quality

Review the last 10 commits on this branch (`git log --oneline main..HEAD`):

- Each commit message must be descriptive (> 10 characters, not just "fix" or "update").
- No commit should contain both unrelated source changes and test changes — one concern per commit.
- No binary files, secrets (`.env`, credentials), or generated artifacts committed.

### Step 7 — Final Verdict

Summarize your findings as:

```
BRANCH VALIDATION REPORT
========================
Branch:        {BRANCH_NAME}
Date:          {DATE}
Agent:         Claude claude-sonnet-4-6

Checks:
  [PASS/FAIL] Branch name format
  [PASS/FAIL] spotless:check
  [PASS/FAIL] Unit tests (./mvnw test)
  [PASS/FAIL] Integration tests (./mvnw verify)
  [PASS/FAIL] Java 26 compliance
  [PASS/FAIL] OTP primitive integrity
  [PASS/FAIL] Test coverage (no regression)
  [PASS/FAIL] Commit quality

Overall: APPROVED / NEEDS WORK / REJECTED

Issues requiring remediation:
  1. <issue description and remediation>
  2. ...
```

A branch is **APPROVED** only if all checks pass.
A branch is **NEEDS WORK** if there are minor issues (formatting, missing annotations, weak commit messages).
A branch is **REJECTED** if any of the following are true:
  - Tests fail
  - Java 26 compliance violations exist in public API surface
  - OTP primitive semantics are broken
  - A secret or credential is committed

---

## Usage

### In Claude Code

Paste this prompt at the start of a new session, replacing `{BRANCH_NAME}` with the actual branch name:

```
Validate branch claude/validate-java-26-refactor-Hd4oY using the branch validation protocol in .claude/branch-validation-prompt.md
```

### In CI/CD (GitHub Actions)

```yaml
- name: Claude Branch Validation
  uses: anthropics/claude-code-action@v1
  with:
    prompt: |
      Validate branch ${{ github.head_ref }} using the protocol in
      .claude/branch-validation-prompt.md. Output the full validation
      report and exit non-zero if the result is REJECTED.
    claude_model: claude-sonnet-4-6
```

### Manual Invocation

```bash
# From the branch to validate:
export BRANCH=$(git branch --show-current)
claude -p "Validate branch $BRANCH using .claude/branch-validation-prompt.md"
```

---

## Remediation Quick Reference

| Failure | Quick Fix |
|---|---|
| spotless:check fails | `./mvnw spotless:apply` |
| Unit test fails | `./mvnw test -Dtest=FailingTestClass` to isolate |
| Integration test fails | `./mvnw verify -Dit.test=FailingIT` |
| Raw type found | Add generic parameters: `List` → `List<String>` |
| Missing module-info entry | Add `exports com.example.newpackage;` to module-info.java |
| `Thread.sleep()` in test | Replace with `Awaitility.await().until(...)` |
| Weak commit message | `git commit --amend -m "descriptive message"` |
| Secret committed | `git filter-repo --path secrets.env --invert-paths` (then force-push with team approval) |
