# Reference: Configuration

JOTP runtime settings and tuning.

## Overview

JOTP's runtime behavior can be configured via Java system properties, environment variables, or programmatic settings.

> **Status:** Coming Soon — Complete configuration options, tuning guidance, and performance implications
>
> **See Also:**
> - [API Overview](api.md) — Core APIs
> - [Troubleshooting](troubleshooting.md) — Common configuration issues
> - [Architecture Overview](../explanations/architecture-overview.md) — System design

## Quick Reference

```bash
# Environment variables
export JOTP_THREAD_POOL_SIZE=16
export JOTP_MESSAGE_QUEUE_CAPACITY=10000
export JOTP_TIMEOUT_DEFAULT_MS=5000

# Java system properties
java -Djotp.threadPoolSize=16 \
     -Djotp.messageQueueCapacity=10000 \
     -Djotp.defaultTimeoutMs=5000 \
     MyApp
```

## Topics Covered (Coming Soon)

- Thread pool configuration (virtual thread count limits)
- Message queue sizing (bounded vs. unbounded)
- Default timeout settings
- Supervisor restart window configuration
- Process registry size limits
- Memory profiling and tuning
- Virtual thread pinning prevention
- Logging configuration
- Performance profiles (latency vs. throughput)

---

**Related:** [Troubleshooting](troubleshooting.md) | [API Overview](api.md)
