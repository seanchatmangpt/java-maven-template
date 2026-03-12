# JOTP Standalone Migration

## Summary

As of 2026-03-11, **JOTP (Java OTP Framework)** has been extracted into a standalone, production-grade library: [`seanchatmangpt/jotp`](https://github.com/seanchatmangpt/jotp)

### What Changed

- ✅ All 15 OTP primitives are now in the external `io.github.seanchatmangpt.jotp` module
- ✅ All JOTP tests are maintained in the standalone project
- ✅ Full documentation, C4 diagrams, and API reference are in the standalone project
- ✅ Maven Central publication ready (`io.github.seanchatmangpt:jotp:1.0.0`)
- ✅ GitHub Actions CI/CD pipeline configured

### What Stayed

- Original JOTP code remains in `org.acme` package (for reference/backward compatibility)
- This template still imports and re-exports the external library
- Dogfood examples and other templates unchanged

---

## Using JOTP

### Option 1: Use External Library (Recommended)

The `java-maven-template` already includes the external JOTP dependency:

```xml
<!-- In pom.xml -->
<dependency>
  <groupId>io.github.seanchatmangpt</groupId>
  <artifactId>jotp</artifactId>
  <version>1.0.0</version>
</dependency>
```

In your code, import from the new package:

```java
import io.github.seanchatmangpt.jotp.proc.Proc;
import io.github.seanchatmangpt.jotp.supervisor.Supervisor;
// ... etc
```

### Option 2: Reference Implementation

The original `org.acme` package classes are still available for reference:

```java
// Still works (same implementation)
import org.acme.Proc;
```

However, new code should use the external library for:
- Cleaner separation of concerns
- Regular maintenance and updates
- Community contributions
- Maven Central availability

---

## JOTP Standalone Project

**Repository:** https://github.com/seanchatmangpt/jotp

**Getting Started:**
```bash
git clone https://github.com/seanchatmangpt/jotp.git
cd jotp
mvn clean verify
```

**Documentation:**
- README.md — Quick start and overview
- docs/ARCHITECTURE-C4-COMPREHENSIVE.md — C4 model diagrams
- docs/GETTING_STARTED.md — Tutorials with examples
- docs/API_REFERENCE.md — Full API reference
- CONTRIBUTING.md — Development guidelines

**Maven Central:**
```xml
<dependency>
  <groupId>io.github.seanchatmangpt</groupId>
  <artifactId>jotp</artifactId>
  <version>1.0.0</version>
</dependency>
```

---

## Migration Path

### For Template Users

If you're using this template and want to migrate to the standalone JOTP:

1. ✅ Update imports from `org.acme` to `io.github.seanchatmangpt.jotp.*`
2. ✅ No code changes needed (API is identical)
3. ✅ Recompile: `mvn clean verify`

### For JOTP Developers

If you're developing JOTP itself:

1. **Clone:** https://github.com/seanchatmangpt/jotp.git
2. **Develop:** All 15 primitives are in `io.github.seanchatmangpt.jotp.*`
3. **Test:** 26 comprehensive test classes included
4. **Publish:** Maven Central (via GitHub Actions)

### For Dogfood Examples

Dogfood templates (innovation engine, patterns, core types) remain in this template and continue to work:

```java
// Still uses JOTP (now from external library)
var result = RefactorEngine.analyze(source);
```

---

## Architecture

```
┌─────────────────────────────────────────────────────────────┐
│ Standalone JOTP Project (seanchatmangpt/jotp)              │
├─────────────────────────────────────────────────────────────┤
│ • 15 OTP Primitives                                        │
│ • Full Test Suite (26 test classes)                        │
│ • Documentation (Architecture, API, Getting Started)       │
│ • CI/CD Pipeline (GitHub Actions)                          │
│ • Maven Central Publication                                │
└──────────────────┬──────────────────────────────────────────┘
                   │
                   │ dependency (Maven)
                   │
                   ▼
┌─────────────────────────────────────────────────────────────┐
│ java-maven-template (this repo)                            │
├─────────────────────────────────────────────────────────────┤
│ • Imports external JOTP library                            │
│ • Keeps org.acme reference implementation                  │
│ • Dogfood examples (innovation, patterns, etc.)            │
│ • Templates for code generation                            │
│ • jgen/ggen integration                                    │
└─────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────┐
│ Your Application                                            │
├─────────────────────────────────────────────────────────────┤
│ • Imports: io.github.seanchatmangpt.jotp.*                │
│ • Uses: Proc, Supervisor, StateMachine, EventManager, etc. │
└─────────────────────────────────────────────────────────────┘
```

---

## Backward Compatibility

### Fully Compatible

✅ All method signatures unchanged
✅ All behavior identical
✅ Drop-in replacement (just update imports)
✅ Module-info.java compatibility

### What Changed

- Package names: `org.acme.*` → `io.github.seanchatmangpt.jotp.*`
- Maven coordinates: `org.acme:lib` (internal) → `io.github.seanchatmangpt:jotp` (external)

---

## Maintenance

### JOTP
- Maintained in: https://github.com/seanchatmangpt/jotp
- Releases: Published to Maven Central automatically
- Issues: Report at https://github.com/seanchatmangpt/jotp/issues

### java-maven-template
- Maintains reference implementation (`org.acme` package)
- Dogfood examples and code generation
- Integration testing with external JOTP

---

## FAQ

**Q: Should I use org.acme or io.github.seanchatmangpt.jotp?**
A: New code should use `io.github.seanchatmangpt.jotp`. The `org.acme` package is provided for reference.

**Q: Is the external JOTP library production-ready?**
A: Yes. Version 1.0.0 includes full test coverage, documentation, and CI/CD pipeline.

**Q: Can I still modify JOTP in this template?**
A: Yes, but changes won't be published. Use the standalone project for upstream contributions.

**Q: Do dogfood examples still work?**
A: Yes. They automatically use the external JOTP library via the Maven dependency.

**Q: How do I report bugs?**
A: For JOTP bugs: https://github.com/seanchatmangpt/jotp/issues
For template issues: Use this repo's issue tracker.

---

## Timeline

| Date | Milestone |
|------|-----------|
| 2026-03-11 | JOTP extracted to standalone project |
| 2026-03-11 | JOTP v1.0.0 released to Maven Central |
| 2026-03-11 | java-maven-template updated to depend on external JOTP |
| Future | JOTP v1.1.0 (RPC framework, clustering primitives) |
| Future | JOTP v2.0.0 (Java 27+ support) |

---

## Summary

JOTP is now a mature, standalone library with:
- ✅ Production-grade code (15 OTP primitives)
- ✅ Comprehensive testing (26 test classes)
- ✅ Full documentation and examples
- ✅ Automated CI/CD and Maven Central publishing
- ✅ Community-ready GitHub repository

This template continues to serve as a reference implementation and testbed for code generation and advanced patterns, while JOTP evolves as an independent library.

---

**For questions or contributions:**
- JOTP: https://github.com/seanchatmangpt/jotp
- Template: https://github.com/seanchatmangpt/java-maven-template
