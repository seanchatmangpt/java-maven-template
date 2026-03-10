# Atlas API → JOTP Message Patterns: Test Results

**Document Status:** ⏳ Pending Test Execution
**Last Updated:** March 2026
**Thesis Reference:** `docs/phd-thesis-atlas-message-patterns.md`

---

## Executive Summary

This document compares theoretical baseline predictions from the Atlas API Message Patterns thesis against actual stress test results from `AtlasAPIStressTest.java`.

### Status Legend
- ✅ **PASS** — Actual throughput exceeds thesis baseline
- ⚠️ **MARGINAL** — Actual throughput between 50%-100% of baseline
- ❌ **FAIL** — Actual throughput below 50% of baseline
- ⏳ **PENDING** — Test not yet executed

---

## Test Results Summary

### SQLRaceAPI Results

| Operation | Pattern | Thesis Baseline | Actual Result | Ratio | Status |
|-----------|---------|-----------------|---------------|-------|--------|
| `Session.Open()` | Command Message | 2M/s | ⏳ | ⏳ | ⏳ |
| `Session.WriteSample()` | Event Message | 100M/s | ⏳ | ⏳ | ⏳ |
| `Session.GetParameters()` | Request-Reply | 78K/s | ⏳ | ⏳ | ⏳ |
| `Session.CreateLap()` | Correlation ID | 500K/s | ⏳ | ⏳ | ⏳ |
| `Session.GetStatistics()` | Document Message | 100K/s | ⏳ | ⏳ | ⏳ |

### FileSessionAPI Results

| Operation | Pattern | Thesis Baseline | Actual Result | Ratio | Status |
|-----------|---------|-----------------|---------------|-------|--------|
| `FileSession.Save()` | Claim Check | 50K/s | ⏳ | ⏳ | ⏳ |
| `FileSession.Load()` | Content Filter | 100K/s | ⏳ | ⏳ | ⏳ |
| `FileSession.Stream()` | Message Sequence | 1M/s | ⏳ | ⏳ | ⏳ |

### DisplayAPI Results

| Operation | Pattern | Thesis Baseline | Actual Result | Ratio | Status |
|-----------|---------|-----------------|---------------|-------|--------|
| `Display.Update()` | Event-Driven Consumer | 1M/s | ⏳ | ⏳ | ⏳ |
| `Plugin.Initialize()` | Service Activator | 10K/s | ⏳ | ⏳ | ⏳ |
| `ToolWindow.Create()` | Message Bus | 100K/s | ⏳ | ⏳ | ⏳ |

### Cross-API Results

| Scenario | Thesis Baseline | Actual Result | Ratio | Status |
|----------|-----------------|---------------|-------|--------|
| Full Pipeline (SQLRace → FileSession → Display) | 500K/s | ⏳ | ⏳ | ⏳ |
| Competing Consumers (10 consumers) | 2M/s | ⏳ | ⏳ | ⏳ |

---

## Detailed Test Output

### SQLRaceAPI Tests

#### Session.Open — Command Message Pattern

**Theoretical Baseline:** 2,000,000 command messages/second

```
[Session.Open] TBD commands in TBD ms = TBD cmd/s
[Session.Open] actual=TBD baseline=2,000,000 ratio=TBD
```

**Analysis:** ⏳ Pending

---

#### Session.WriteSample — Event Message Pattern

**Theoretical Baseline:** 100,000,000 event deliveries/second

```
[WriteSample] events=TBD handlers=TBD deliveries=TBD in TBD ms = TBD deliveries/s
[WriteSample] actual=TBD baseline=100,000,000 ratio=TBD
```

**Analysis:** ⏳ Pending

---

#### Session.GetParameters — Request-Reply Pattern

**Theoretical Baseline:** 78,000 round-trips/second

```
[GetParameters] TBD round-trips in TBD ms = TBD rt/s
[GetParameters] actual=TBD baseline=78,000 ratio=TBD
```

**Analysis:** ⏳ Pending

---

#### Session.CreateLap — Correlation ID Pattern

**Theoretical Baseline:** 500,000 correlations/second

```
[CreateLap] TBD lap correlations in TBD ms = TBD corr/s
[CreateLap] actual=TBD baseline=500,000 ratio=TBD
```

**Analysis:** ⏳ Pending

---

#### Session.GetStatistics — Document Message Pattern

**Theoretical Baseline:** 100,000 document queries/second

```
[GetStatistics] TBD document queries in TBD ms = TBD queries/s
[GetStatistics] actual=TBD baseline=100,000 ratio=TBD
```

**Analysis:** ⏳ Pending

---

### FileSessionAPI Tests

#### FileSession.Save — Claim Check Pattern

**Theoretical Baseline:** 50,000 saves/second

```
[FileSave] TBD claim checks in TBD ms = TBD saves/s
[FileSave] actual=TBD baseline=50,000 ratio=TBD
```

**Analysis:** ⏳ Pending

---

#### FileSession.Load — Content Filter Pattern

**Theoretical Baseline:** 100,000 loads/second

```
[FileLoad] TBD filtered loads in TBD ms = TBD loads/s
[FileLoad] actual=TBD baseline=100,000 ratio=TBD
```

**Analysis:** ⏳ Pending

---

#### FileSession.Stream — Message Sequence Pattern

**Theoretical Baseline:** 1,000,000 stream items/second

```
[FileStream] batches=TBD items/batch=TBD total=TBD in TBD ms = TBD items/s
[FileStream] actual=TBD baseline=1,000,000 ratio=TBD
```

**Analysis:** ⏳ Pending

---

### DisplayAPI Tests

#### Display.Update — Event-Driven Consumer Pattern

**Theoretical Baseline:** 1,000,000 updates/second

```
[DisplayUpdate] TBD updates in TBD ms = TBD updates/s
[DisplayUpdate] actual=TBD baseline=1,000,000 ratio=TBD
```

**Analysis:** ⏳ Pending

---

#### Plugin.Initialize — Service Activator Pattern

**Theoretical Baseline:** 10,000 activations/second

```
[PluginInit] TBD activations in TBD ms = TBD activations/s
[PluginInit] actual=TBD baseline=10,000 ratio=TBD
```

**Analysis:** ⏳ Pending

---

#### ToolWindow.Create — Message Bus Pattern

**Theoretical Baseline:** 100,000 creates/second

```
[ToolWindowCreate] TBD creates in TBD ms = TBD creates/s
[ToolWindowCreate] actual=TBD baseline=100,000 ratio=TBD
```

**Analysis:** ⏳ Pending

---

### Cross-API Tests

#### Full Pipeline — SQLRace → FileSession → Display

**Theoretical Baseline:** 500,000 samples/second through all three APIs

```
[FullPipeline] TBD samples through 3 APIs in TBD ms = TBD samples/s
[FullPipeline] actual=TBD baseline=500,000 ratio=TBD
```

**Analysis:** ⏳ Pending

---

#### Competing Consumers — 10 Parallel Consumers

**Theoretical Baseline:** 2,000,000 consume/second

```
[CompetingConsumers] consumers=10 messages=TBD in TBD ms = TBD consume/s
[CompetingConsumers] actual=TBD baseline=2,000,000 ratio=TBD
```

**Analysis:** ⏳ Pending

---

## Analysis Summary

### Overall Results

| Category | Tests | Pass | Marginal | Fail | Pending |
|----------|-------|------|----------|------|---------|
| SQLRaceAPI | 5 | 0 | 0 | 0 | 5 |
| FileSessionAPI | 3 | 0 | 0 | 0 | 3 |
| DisplayAPI | 3 | 0 | 0 | 0 | 3 |
| Cross-API | 2 | 0 | 0 | 0 | 2 |
| **Total** | **13** | **0** | **0** | **0** | **13** |

### Performance Distribution

⏳ Pending test execution...

---

## Conclusions

⏳ Conclusions will be drawn after test execution.

---

## How to Run Tests

```bash
# Run integration tests
mvnd verify -Dit.test='AtlasAllAPIsMessagePatternsIT'

# Run stress tests
mvnd verify -Dtest='AtlasAPIStressTest'

# Run all tests
mvnd verify

# Capture output to update this document
mvnd verify -Dtest='AtlasAPIStressTest' 2>&1 | tee test-output.log
```

---

## Appendix: Proven Baselines (Reference)

From `ReactiveMessagingPatternStressTest.java`:

| Pattern | JOTP Primitive | Proven Baseline |
|---------|----------------|-----------------|
| Message Channel | `Proc.tell()` | **30.1M msg/s** |
| Event Fanout | `EventManager.notify()` | **1.1B events/s** |
| Request-Reply | `Proc.ask()` | **78K roundtrip/s** |
| Competing Consumers | 10 concurrent `Proc` | **2.2M consume/s** |
| Supervised Restart | `Supervisor` | **sub-15ms cascade** |

---

*Document will be updated after test execution.*
