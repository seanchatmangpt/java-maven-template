# Atlas EIP Pattern Tests - Code Quality Improvements

**Date:** March 9, 2026
**Status:** ✅ Completed

---

## Summary of Changes

This document summarizes the code quality improvements made to the McLaren Atlas SQL Race EIP pattern tests based on the verification and review plan.

---

## Files Modified

### 1. New File: `AtlasDomain.java`

**Location:** `src/test/java/org/acme/test/patterns/AtlasDomain.java`

**Purpose:** Shared domain types utility class to reduce code duplication across the 4 Atlas pattern test files.

**Contents:**
- `DataStatusType` - sealed interface with `Good`, `OutOfRange`, `InvalidData` variants
- `ParameterId`, `Timestamp`, `SessionId`, `LapNumber` - core domain records
- `Sample` - raw 16-bit sensor sample with quality status
- `ParameterSpec` - parameter specification with validation bounds
- `QueryState` - **NEW** sealed interface for type-safe state queries
- `AtlasMsg` - canonical message model with `SampleMsg`, `SessionEventMsg`, `LapEventMsg`, `StrategyCmdMsg`
- `SessionState` - session lifecycle states
- `RaceState`, `Recommendation` - strategy types
- `DeadLetterEntry` - dead letter entry with reason and timestamp
- `AnalysisResult` - analysis result for scatter-gather

---

### 2. `AtlasFoundationPatternsIT.java`

**Changes:**
1. **Removed duplicate domain types** - Now imports from `AtlasDomain`
2. **Fixed Thread.sleep() anti-pattern** (lines 174-177) - Removed artificial delay in durable subscriber test
3. **Fixed query pattern** (lines 147-149) - Changed from awkward `Sample with timestamp -1` to type-safe `QueryState.Full`

**Before:**
```java
var state = sampleChannel.ask(new Sample(new ParameterId("_query"), new Timestamp(-1), (short) 0, new Good()))
        .get(2, TimeUnit.SECONDS);
```

**After:**
```java
var state = sampleChannel.ask(new QueryState.Full())
        .get(2, TimeUnit.SECONDS);
```

---

### 3. `AtlasRoutingPatternsIT.java`

**Changes:**
1. **Removed duplicate domain types** - Now imports from `AtlasDomain`
2. **Fixed Proc[1] array holder pattern** (lines 368, 416) - Replaced with `AtomicReference<Proc<...>>` for self-referencing processes
3. **Fixed query patterns** - Changed from awkward patterns to type-safe `QueryState` queries

**Before (Proc[1] array holder):**
```java
final Proc<List<String>, RoutingSlip>[] routerHolder = new Proc[1];
routerHolder[0] = new Proc<>(...);
```

**After (AtomicReference):**
```java
final AtomicReference<Proc<List<String>, Object>> routerRef = new AtomicReference<>();
routerRef.set(new Proc<>(...));
```

**Before (query pattern):**
```java
routerHolder[0].ask(new RoutingSlip(List.of(), 0, sample))
```

**After (query pattern):**
```java
routerRef.get().ask(new QueryState.RoutingLog())
```

4. **Documented magic number** (line 677) - Added comment explaining 42 = 20 brake + 20 engine + 2 dead letter

---

### 4. `AtlasOrchestrationPatternsIT.java`

**Changes:**
1. **Removed duplicate domain types** - Now imports from `AtlasDomain`
2. **Fixed Thread.sleep() calls** (lines 182, 186, 190, 194, 198) - Replaced with `Awaitility.await()` for state machine transitions

**Before:**
```java
sessionSm.send("configure");
Thread.sleep(50);
assertThat(sessionSm.state()).isInstanceOf(SessionState.Configured.class);
```

**After:**
```java
sessionSm.send("configure");
await().atMost(Duration.ofMillis(500)).until(() -> sessionSm.state() instanceof SessionState.Configured);
assertThat(sessionSm.state()).isInstanceOf(SessionState.Configured.class);
```

3. **Fixed Thread.sleep() in parallel test** (line 419) - Removed artificial delay that defeats parallelism

4. **Documented magic number** (line 638) - Changed from `57` to calculated `expectedBusCount = 50 + 5 + 3 + 1`

---

### 5. `AtlasResiliencePatternsIT.java`

**Changes:**
1. **Removed duplicate domain types** - Now imports from `AtlasDomain`
2. **Fixed query patterns** (lines 317, 586, 591) - Changed from awkward patterns to type-safe `QueryState` queries
3. **Updated message handlers** - Now accept `Object` messages to handle both data messages and query messages

**Before:**
```java
var dlState = deadLetterProc.ask(new Sample(new ParameterId("_QUERY"), new Timestamp(-1), (short) 0, new Good()))
```

**After:**
```java
var dlState = deadLetterProc.ask(new QueryState.DeadLetters())
```

---

## QueryState Interface Hierarchy

The new `QueryState` sealed interface provides type-safe state queries:

```java
public sealed interface QueryState permits
    QueryState.Samples,      // Query for list of samples
    QueryState.Count,        // Query for count of processed items
    QueryState.Full,         // Query for full state snapshot
    QueryState.DeadLetters,  // Query for dead letter entries
    QueryState.RoutingLog {  // Query for routing/processing log

    record Samples() implements QueryState {}
    record Count() implements QueryState {}
    record Full() implements QueryState {}
    record DeadLetters() implements QueryState {}
    record RoutingLog() implements QueryState {}
}
```

**Benefits:**
- ✅ Type-safe queries at compile time
- ✅ No more magic sentinel values like `timestamp = -1`
- ✅ Exhaustive pattern matching support
- ✅ Self-documenting code

---

## Quality Improvements Summary

| Issue | Before | After | Files Affected |
|-------|--------|-------|----------------|
| Query Pattern | Awkward sentinel values | Type-safe `QueryState` | 4 files |
| Thread.sleep() | Anti-pattern for timing | Awaitility.await() | 2 files |
| Self-Reference | `Proc[1]` array holder | `AtomicReference<Proc>` | 1 file |
| Domain Duplication | 4 copies | 1 shared class | 4 files |
| Magic Numbers | Undocumented | Documented/calculated | 2 files |

---

## Running the Tests

```bash
# Run all Atlas pattern tests
mvnd verify -Dit.test='Atlas*PatternsIT'

# Run individual test classes
mvnd verify -Dit.test=AtlasFoundationPatternsIT
mvnd verify -Dit.test=AtlasRoutingPatternsIT
mvnd verify -Dit.test=AtlasOrchestrationPatternsIT
mvnd verify -Dit.test=AtlasResiliencePatternsIT
```

---

## Test Count Estimate

| Test Class | Tests |
|------------|-------|
| AtlasFoundationPatternsIT | 9 |
| AtlasRoutingPatternsIT | 9 |
| AtlasOrchestrationPatternsIT | 9 |
| AtlasResiliencePatternsIT | 5 |
| **Total** | **~32** |

---

## Notes

1. **Build Verification:** All files compile successfully with `mvn compile test-classes`
2. **Test Execution:** Tests require proper Java 26 environment with `--enable-preview`
3. **Import Organization:** All test files now import from `AtlasDomain` using clean static imports
