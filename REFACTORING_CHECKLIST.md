# JOTP Refactoring Checklist

**Project:** Java OTP (JOTP)
**Refactoring Date:** 2026-03-12
**Branch:** `claude/add-c4-jotp-diagrams-YaiTu`

This checklist guides you through pre-commit, post-commit, and manual testing phases to ensure the refactoring is complete and correct.

---

## Phase 1: Pre-Commit Verification

Run these checks BEFORE committing any changes.

### 1.1 Code Structure Verification

- [ ] **Namespace Check:** Verify all Java source files are in `src/main/java/io/github/seanchatmangpt/jotp/`
  ```bash
  ls -R src/main/java/io/github/seanchatmangpt/jotp/
  ```
  Expected: Core, util, exception, and dogfood packages visible.

- [ ] **Old Namespace Cleanup:** Confirm no files remain in old `src/main/java/org/acme/`
  ```bash
  test ! -d src/main/java/org/acme && echo "✓ Cleaned up"
  ```
  Expected: Directory does not exist or is empty.

- [ ] **Test Structure:** Verify test files match new namespace
  ```bash
  ls -R src/test/java/io/github/seanchatmangpt/jotp/
  ```
  Expected: Test packages mirror main package structure.

- [ ] **Module Descriptor Exists**
  ```bash
  cat src/main/java/module-info.java
  ```
  Expected: Contains `module io.github.seanchatmangpt.jotp`

### 1.2 Configuration File Verification

- [ ] **pom.xml - GroupId Updated**
  ```bash
  grep "<groupId>io.github.seanchatmangpt</groupId>" pom.xml
  ```
  Expected: GroupId is `io.github.seanchatmangpt`

- [ ] **pom.xml - ArtifactId Correct**
  ```bash
  grep "<artifactId>jotp</artifactId>" pom.xml
  ```
  Expected: ArtifactId is `jotp`

- [ ] **pom.xml - Version Updated**
  ```bash
  grep "<version>2.0.0</version>" pom.xml
  ```
  Expected: Version reflects new release (e.g., 2.0.0)

- [ ] **Java Compiler Target**
  ```bash
  grep -A 1 "maven.compiler.source" pom.xml
  ```
  Expected: Source and target are both `26`

- [ ] **Enable Preview Flag**
  ```bash
  grep "\-\-enable-preview" pom.xml
  ```
  Expected: Compiler args include `--enable-preview`

- [ ] **Module Declaration in POM**
  ```bash
  grep "<module>" pom.xml
  ```
  Expected: If multi-module, list all modules

- [ ] **CLAUDE.md Updated**
  ```bash
  grep "io.github.seanchatmangpt.jotp" CLAUDE.md | head -5
  ```
  Expected: All package references updated in documentation

### 1.3 Java Import Verification

- [ ] **No org.acme imports in main code**
  ```bash
  grep -r "import org.acme" src/main/java/ 2>/dev/null | wc -l
  ```
  Expected: Output is `0`

- [ ] **No org.acme imports in test code**
  ```bash
  grep -r "import org.acme" src/test/java/ 2>/dev/null | wc -l
  ```
  Expected: Output is `0`

- [ ] **All imports use new namespace**
  ```bash
  grep -r "import io.github.seanchatmangpt.jotp" src/main/java/ | head -5
  ```
  Expected: Multiple imports visible

- [ ] **No wildcard imports (best practice)**
  ```bash
  grep -r "import io.github.seanchatmangpt.jotp.\*" src/
  ```
  Expected: No output (wildcard imports avoided)

### 1.4 Documentation Verification

- [ ] **MIGRATION_GUIDE.md exists**
  ```bash
  ls -la docs/MIGRATION_GUIDE.md
  ```
  Expected: File exists and contains migration instructions

- [ ] **ARCHITECTURE.md updated**
  ```bash
  grep "io.github.seanchatmangpt.jotp" docs/ARCHITECTURE.md
  ```
  Expected: Documentation uses new namespace

- [ ] **OTP_PRIMITIVES.md exists**
  ```bash
  ls -la docs/OTP_PRIMITIVES.md
  ```
  Expected: File exists

- [ ] **README.md mentions new namespace**
  ```bash
  grep "io.github.seanchatmangpt" README.md
  ```
  Expected: README includes new coordinates

---

## Phase 2: Pre-Build Checks

Run before the first build to catch configuration issues early.

### 2.1 Java Version Check

- [ ] **Correct JDK Installed**
  ```bash
  java -version
  ```
  Expected: Shows Java 26 or later (e.g., "openjdk version 26" or GraalVM 25.0.2)

- [ ] **JAVA_HOME Set Correctly**
  ```bash
  echo $JAVA_HOME
  ```
  Expected: Points to Java 26 installation

- [ ] **Maven Uses Correct Java**
  ```bash
  ./mvnw -version
  ```
  Expected: Shows Java 26 in Maven runtime

### 2.2 Maven Configuration

- [ ] **Maven Wrapper Works**
  ```bash
  ./mvnw --version
  ```
  Expected: Maven version and Java version displayed

- [ ] **Settings.xml Proxy (if needed)**
  ```bash
  cat ~/.m2/settings.xml | grep -A 5 "<proxy"
  ```
  Expected: Proxy configured if required by environment

- [ ] **Maven Daemon Installed** (optional, recommended)
  ```bash
  mvnd --version
  ```
  Expected: Maven Daemon version 2.0.0-rc-3 or later (or "command not found" is okay)

- [ ] **.mvn/daemon.properties exists**
  ```bash
  ls -la .mvn/daemon.properties
  ```
  Expected: File exists

---

## Phase 3: Build Verification

Run these commands in order to verify the build succeeds.

### 3.1 Clean Compile

- [ ] **Clean Previous Builds**
  ```bash
  ./mvnw clean
  ```
  Expected: `BUILD SUCCESS` and `target/` directory cleaned

- [ ] **Compile Main Code**
  ```bash
  ./mvnw compile
  ```
  Expected: `BUILD SUCCESS` with no compilation errors
  - If fails: Check Java version and compiler flags
  - If fails: Verify module-info.java syntax

- [ ] **Compile Test Code**
  ```bash
  ./mvnw test-compile
  ```
  Expected: `BUILD SUCCESS` with test classes compiled

### 3.2 Unit Tests

- [ ] **Run Unit Tests**
  ```bash
  ./mvnw test
  ```
  Expected: `BUILD SUCCESS` with all tests passing
  - Count tests: Look for "Tests run: X"
  - If failures: Check for namespace-related import issues
  - If failures: Verify test configuration in junit-platform.properties

- [ ] **Test Code Formatting** (should auto-apply on edit, but verify)
  ```bash
  ./mvnw spotless:check
  ```
  Expected: `BUILD SUCCESS` (all code formatted correctly)
  - If fails: Run `./mvnw spotless:apply` to auto-fix

- [ ] **Check Parallel Execution** (from test output)
  - Expected: Tests report parallel execution in output
  - Look for: "parallel mode enabled"

### 3.3 Integration Tests

- [ ] **Run Full Verification** (unit + integration + checks)
  ```bash
  ./mvnw verify
  ```
  Expected: `BUILD SUCCESS` with all phases passing
  - Duration: Usually 2-5 minutes depending on test count
  - Includes: compile → test → verify → integration tests

- [ ] **Dogfood Verification** (if available)
  ```bash
  ./mvnw verify -Ddogfood
  ```
  Expected: `BUILD SUCCESS` including dogfood template examples

### 3.4 Package Building

- [ ] **Build JAR Package**
  ```bash
  ./mvnw package
  ```
  Expected: `BUILD SUCCESS` with `target/jotp-2.0.0.jar` created

- [ ] **Verify JAR Contents**
  ```bash
  jar tf target/jotp-2.0.0.jar | grep "io/github/seanchatmangpt/jotp" | head -5
  ```
  Expected: Shows new namespace classes in JAR

- [ ] **Build Fat JAR** (if shade profile exists)
  ```bash
  ./mvnw package -Dshade
  ```
  Expected: `BUILD SUCCESS` with `jotp-2.0.0-uber.jar` created

### 3.5 Build with Maven Daemon (Optional)

- [ ] **Install Maven Daemon** (if not present)
  ```bash
  # Download and symlink mvnd, or use mvnd from PATH
  mvnd --version 2>/dev/null || echo "mvnd not installed"
  ```

- [ ] **Verify Daemon Build Works**
  ```bash
  bin/mvndw clean verify
  ```
  Expected: `BUILD SUCCESS` (same as Maven but faster)
  - Duration: Should be 30-50% faster than standard `./mvnw`

---

## Phase 4: Code Quality Verification

### 4.1 Code Formatting

- [ ] **Spotless Check Passes**
  ```bash
  ./mvnw spotless:check
  ```
  Expected: `BUILD SUCCESS` (all code formatted)

- [ ] **No Code Style Violations**
  - Check output for: "all files are properly formatted"
  - If fails: Run `./mvnw spotless:apply` (auto-applies formatting)

### 4.2 Architecture Rules (if ArchUnit tests exist)

- [ ] **Architecture Tests Pass**
  ```bash
  ./mvnw test -Dtest=*Architecture*
  ```
  Expected: All architecture constraints satisfied
  - Checks: Layer separation, naming conventions, etc.

### 4.3 Code Coverage (if JaCoCo configured)

- [ ] **Code Coverage Report Generated**
  ```bash
  ./mvnw clean verify jacoco:report 2>/dev/null || echo "JaCoCo not configured"
  ```
  Expected: Coverage report in `target/site/jacoco/`

---

## Phase 5: Git Pre-Commit Tasks

Complete before staging files for commit.

### 5.1 Status Check

- [ ] **Review git status**
  ```bash
  git status
  ```
  Expected: See all modified files, untracked new files

- [ ] **Review Changes** (inspect what will be committed)
  ```bash
  git diff --stat
  ```
  Expected: Summary shows appropriate file changes
  - Should see: Many Java files in new namespace
  - Should NOT see: Binary files or large non-code files

### 5.2 Pre-commit Hook Simulation

- [ ] **Code Formatting Check**
  ```bash
  ./mvnw spotless:check
  ```
  Expected: `BUILD SUCCESS`

- [ ] **Build Check**
  ```bash
  ./mvnw clean verify
  ```
  Expected: `BUILD SUCCESS`

### 5.3 Branch Verification

- [ ] **Correct Branch**
  ```bash
  git branch
  ```
  Expected: Currently on `claude/add-c4-jotp-diagrams-YaiTu`

- [ ] **Branch Tracking** (if applicable)
  ```bash
  git branch -vv
  ```
  Expected: Branch info shown

---

## Phase 6: Git Commit Instructions

Execute these commands in order.

### 6.1 Commit 1: Build Configuration

**Purpose:** Separate build configuration changes from code changes for easier review

```bash
# Stage build configuration files only
git add pom.xml
git add module-info.java
git add .mvn/daemon.properties
git add .mvn/extensions.xml
git add src/test/resources/junit-platform.properties
git add .claude/settings.json

# Verify staging
git status

# Commit with message
git commit -m "Refactor: Update build configuration for io.github.seanchatmangpt.jotp namespace"
```

**Expected:** Commit succeeds with message about build configuration

### 6.2 Commit 2: Source Code Migration

**Purpose:** Migrate all Java source files to new namespace

```bash
# Stage all Java source files (main + test)
git add src/main/java/io/github/seanchatmangpt/jotp/
git add src/test/java/io/github/seanchatmangpt/jotp/

# Verify staging
git status

# Commit with message
git commit -m "Refactor: Migrate source code from org.acme to io.github.seanchatmangpt.jotp namespace

- Move 216 Java files to new package structure
- Update all imports to use new namespace
- Update 15 OTP primitives: Proc, ProcRef, Supervisor, etc.
- Update dogfood implementations and test suites
- Maintain all functionality and test coverage"
```

**Expected:** Commit succeeds with detailed message

### 6.3 Commit 3: Documentation Updates

**Purpose:** Update documentation to reflect new namespace

```bash
# Stage documentation files
git add docs/MIGRATION_GUIDE.md
git add docs/ARCHITECTURE.md
git add docs/OTP_PRIMITIVES.md
git add CLAUDE.md
git add README.md
git add REFACTORING_SUMMARY.md

# Verify staging
git status

# Commit with message
git commit -m "Docs: Update documentation for io.github.seanchatmangpt.jotp namespace

- Update CLAUDE.md with new package structure
- Create MIGRATION_GUIDE.md for users upgrading from org.acme
- Update ARCHITECTURE.md with new module structure
- Create REFACTORING_SUMMARY.md with detailed change documentation
- Update README.md with new artifact coordinates"
```

**Expected:** Commit succeeds with documentation updates

### 6.4 Commit 4: Build Scripts and Configuration

**Purpose:** Update utility scripts and remaining configuration

```bash
# Stage build scripts
git add bin/mvndw
git add bin/jgen
git add bin/dogfood
git add dx.sh
git add dx-standalone.sh

# Verify staging
git status

# Commit with message
git commit -m "Chore: Update build scripts and utilities for Maven Daemon integration

- Update mvndw wrapper for faster builds
- Configure Maven Daemon (mvnd 2.0.0-rc-3) integration
- Update jgen and dogfood scripts for new namespace
- Configure build cache and parallel execution"
```

**Expected:** Commit succeeds with script updates

---

## Phase 7: Pre-Push Verification

Run these checks before pushing to remote.

### 7.1 Local Branch Verification

- [ ] **All 4 Commits Present**
  ```bash
  git log --oneline -5
  ```
  Expected: Shows 4 new commits related to refactoring

- [ ] **Commits in Correct Order**
  1. Build configuration
  2. Source code migration
  3. Documentation updates
  4. Build scripts

- [ ] **Commit Messages Are Clear**
  ```bash
  git log --format="%h %s" -5
  ```
  Expected: Readable, descriptive commit messages

### 7.2 Build Verification Before Push

- [ ] **Clean Build on Current Commit**
  ```bash
  ./mvnw clean verify
  ```
  Expected: `BUILD SUCCESS`

- [ ] **No Unstaged Changes**
  ```bash
  git status
  ```
  Expected: "working tree clean"

- [ ] **All Tests Pass**
  ```bash
  ./mvnw test
  ```
  Expected: All tests passing

---

## Phase 8: Push Instructions

### 8.1 Push Branch

```bash
# Push to remote with -u flag to set upstream
git push -u origin claude/add-c4-jotp-diagrams-YaiTu

# Or, if branch tracking already set:
git push origin claude/add-c4-jotp-diagrams-YaiTu
```

**Expected:** Branch uploaded to remote successfully

### 8.2 Create Pull Request (GitHub)

```bash
# If using GitHub CLI (gh)
gh pr create --title "Refactor: Namespace migration from org.acme to io.github.seanchatmangpt.jotp" \
  --body "$(cat <<'EOF'
## Summary
- Comprehensive namespace refactoring from `org.acme` to `io.github.seanchatmangpt.jotp`
- 216 Java files migrated with updated imports
- Updated build configuration for Maven 4 + Maven Daemon
- Full test coverage maintained
- Java 26 target with preview features enabled

## Test Plan
- [x] All unit tests pass (`./mvnw test`)
- [x] All integration tests pass (`./mvnw verify`)
- [x] Code formatting checks pass
- [x] JAR builds successfully
- [x] Dogfood templates compile and test
- [x] Documentation updated with migration guide

## Breaking Changes
- Package names changed: `org.acme.*` → `io.github.seanchatmangpt.jotp.*`
- Maven coordinates updated: `io.github.seanchatmangpt:jotp:2.0.0`
- Requires Java 26 with `--enable-preview` flag
- Module system enforced (JPMS)

## Files Modified
- Java source: 216 files
- Configuration: 6 files
- Documentation: 5 files
- Build scripts: 5 files

Resolves: #TODO (add issue number if applicable)
EOF
)"
```

---

## Phase 9: Post-Commit Verification

After commits are created, verify integrity.

### 9.1 Commit Content Verification

- [ ] **Check Commit 1: Build Configuration**
  ```bash
  git show HEAD~3 --stat
  ```
  Expected: pom.xml, module-info.java, and config files

- [ ] **Check Commit 2: Source Code**
  ```bash
  git show HEAD~2 --stat
  ```
  Expected: 216 Java files with path changes

- [ ] **Check Commit 3: Documentation**
  ```bash
  git show HEAD~1 --stat
  ```
  Expected: Documentation files updated

- [ ] **Check Commit 4: Build Scripts**
  ```bash
  git show HEAD --stat
  ```
  Expected: Binary/script files updated

### 9.2 Interdiff Verification

- [ ] **Compare Namespaces Across Commits**
  ```bash
  git log --all --oneline --graph -5
  ```
  Expected: Linear history with 4 new commits

- [ ] **Verify No File Conflicts**
  ```bash
  git log --oneline -20 | grep -i "merge"
  ```
  Expected: No merge commits (linear history)

---

## Phase 10: Post-Push Verification

After pushing to remote.

### 10.1 Remote Branch Verification

- [ ] **Branch Exists on Remote**
  ```bash
  git branch -r | grep claude/add-c4-jotp-diagrams-YaiTu
  ```
  Expected: Branch visible in remote list

- [ ] **All Commits Pushed**
  ```bash
  git log origin/claude/add-c4-jotp-diagrams-YaiTu --oneline -5
  ```
  Expected: Shows 4 new refactoring commits

- [ ] **No Unpushed Commits**
  ```bash
  git log --oneline @{u}..
  ```
  Expected: No output (all commits pushed)

### 10.2 Pull Request Verification (if created)

- [ ] **PR Successfully Created**
  - Check GitHub/GitLab for new PR
  - Expected: Shows 4 commits in PR

- [ ] **PR Checks Running**
  - Expected: CI/CD pipeline triggered
  - Look for: Build logs, test results, code review

- [ ] **All PR Checks Pass**
  - Expected: Green checkmarks on all CI checks
  - Duration: Usually 5-10 minutes

---

## Phase 11: Manual Testing Steps

After build verification, test functionality.

### 11.1 Core Functionality Testing

- [ ] **Test Proc Implementation**
  ```bash
  ./mvnw test -Dtest=ProcTest
  ```
  Expected: All Proc tests pass

- [ ] **Test Supervisor**
  ```bash
  ./mvnw test -Dtest=SupervisorTest
  ```
  Expected: Supervision tree tests pass

- [ ] **Test Result<T,E> Railway**
  ```bash
  ./mvnw test -Dtest=ResultTest
  ```
  Expected: Result monad tests pass

### 11.2 Integration Testing

- [ ] **Test Process Linking**
  ```bash
  ./mvnw test -Dtest=ProcessLinkIT
  ```
  Expected: Link semantics verified

- [ ] **Test Registry Operations**
  ```bash
  ./mvnw test -Dtest=ProcessRegistryIT
  ```
  Expected: Registry tests pass

- [ ] **Test Event Manager**
  ```bash
  ./mvnw test -Dtest=EventManagerIT
  ```
  Expected: Event dispatch tests pass

### 11.3 Dogfood Template Testing

- [ ] **Generate Dogfood Examples**
  ```bash
  bin/dogfood generate
  ```
  Expected: All dogfood examples generated

- [ ] **Dogfood Coverage Report**
  ```bash
  bin/dogfood report
  ```
  Expected: Shows coverage across template categories

- [ ] **Full Dogfood Verification**
  ```bash
  ./mvnw verify -Ddogfood
  ```
  Expected: All templates compile and test

### 11.4 Build Artifact Testing

- [ ] **Inspect Generated JAR**
  ```bash
  jar -tf target/jotp-2.0.0.jar | wc -l
  ```
  Expected: Shows class count (typically 100+ classes)

- [ ] **Verify Module in JAR**
  ```bash
  jar -tf target/jotp-2.0.0.jar | grep module-info.class
  ```
  Expected: Module descriptor present

- [ ] **Test as Dependency** (if applicable)
  ```bash
  # In a test project:
  mvn install:install-file -Dfile=target/jotp-2.0.0.jar \
    -DgroupId=io.github.seanchatmangpt \
    -DartifactId=jotp \
    -Dversion=2.0.0 \
    -Dpackaging=jar
  ```

---

## Phase 12: Rollback Preparation

Keep these instructions handy in case rollback is needed.

### 12.1 Pre-Rollback Verification

- [ ] **Document Current State**
  ```bash
  git log --oneline -10 > current-state.txt
  git status > current-status.txt
  ```
  Helps verify what you're rolling back from.

- [ ] **Backup Any Uncommitted Work**
  ```bash
  git stash
  ```
  Preserves any uncommitted changes.

### 12.2 Rollback Command (if needed)

- [ ] **Revert Last 4 Commits** (if mistakes found)
  ```bash
  git revert HEAD~3..HEAD
  ```
  Creates new commits undoing the refactoring.

- [ ] **Hard Reset to Before Refactoring** (if not pushed)
  ```bash
  git reset --hard origin/main
  ```
  Discards all refactoring changes.

- [ ] **Create Rollback Branch**
  ```bash
  git checkout -b rollback/from-jotp-refactoring
  git reset --hard <pre-refactoring-commit-hash>
  git push -u origin rollback/from-jotp-refactoring
  ```

---

## Troubleshooting Guide

### Compilation Errors

**Issue:** "cannot find symbol" for new namespace imports
**Solution:**
```bash
./mvnw clean compile  # Clean and rebuild
```

**Issue:** Module not found errors
**Solution:**
```bash
# Verify module-info.java exists and is correct
cat src/main/java/module-info.java
# Check that all exports match actual packages
```

### Test Failures

**Issue:** Tests fail after refactoring
**Solution:**
```bash
# Run single failing test for details
./mvnw test -Dtest=FailingTestClass

# Check for namespace import issues in test files
grep "import org.acme" src/test/java/ 2>/dev/null
```

**Issue:** Resource not found in tests
**Solution:**
```bash
# Verify test resources path
ls -la src/test/resources/
```

### Build Performance Issues

**Issue:** Build is slow
**Solution:**
```bash
# Use Maven Daemon for faster builds
bin/mvndw verify

# Or pre-warm cache
mvnd compile -q -T1C
```

### Java Version Issues

**Issue:** "Java version not supported"
**Solution:**
```bash
# Verify Java 26 is installed
java -version

# Check JAVA_HOME
echo $JAVA_HOME

# Force Maven to use correct Java
JAVA_HOME=/path/to/java26 ./mvnw verify
```

---

## Sign-Off Checklist

Complete this section before declaring refactoring complete.

- [ ] All 4 commits created successfully
- [ ] All 4 commits pushed to remote
- [ ] Pull request created (if applicable)
- [ ] All CI/CD checks passing
- [ ] All tests passing locally and remotely
- [ ] Code review approval received
- [ ] Merge to main branch completed
- [ ] Production deployment successful (if applicable)
- [ ] Monitoring shows no errors in production
- [ ] Team notified of namespace change
- [ ] Dependency updates communicated to users

---

**Checklist Version:** 1.0
**Last Updated:** 2026-03-12
**Project:** JOTP Refactoring `org.acme` → `io.github.seanchatmangpt.jotp`
