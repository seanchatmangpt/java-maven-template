# Build & Toolchain Reference

**Build tool:** `mvnd` (Maven Daemon 2.0.0-rc-3, bundling Maven 4)
**JDK:** GraalVM Community CE 25.0.2 (Java 26 EA)
**Module system:** Java Platform Module System (JPMS)
**Compile flags:** `--enable-preview` (compile and runtime)

---

## Build Commands

### Core Lifecycle

| Command | Phase | Description |
|---|---|---|
| `./mvnw test` | `test` | Compiles sources and runs unit tests (`*Test.java`) via maven-surefire-plugin. |
| `./mvnw verify` | `verify` | Runs unit tests + integration tests (`*IT.java`) + quality checks (Spotless). |
| `./mvnw package` | `package` | Produces `target/<artifact>.jar` (no dependencies). |
| `./mvnw package -Dshade` | `package` | Produces a fat/uber JAR via the Maven Shade plugin. |
| `./mvnw compile` | `compile` | Compiles main sources only. |
| `./mvnw clean` | `clean` | Deletes `target/`. |

### Code Quality

| Command | Description |
|---|---|
| `./mvnw spotless:apply` | Formats all Java sources using Google Java Format (AOSP style). Modifies files in-place. |
| `./mvnw spotless:check` | Checks formatting without modifying files. Fails the build if any file is unformatted. Runs automatically at the `compile` phase. |

### Testing

| Command | Description |
|---|---|
| `mvnd test -Dtest=ProcTest` | Runs the single test class `ProcTest`. |
| `mvnd verify -Dit.test=FooIT` | Runs the single integration test class `FooIT`. |
| `./mvnw verify -Ddogfood` | Full dogfood pipeline: generate-check + compile + test + report. |

### Other

| Command | Description |
|---|---|
| `./mvnw jshell:run` | Launches a JShell REPL with the project's compiled classes on the classpath. |
| `bin/mvndw verify` | Maven Daemon wrapper equivalent of `./mvnw verify` (persistent JVM, faster). |
| `mvnd compile -q -T1C` | Pre-warms the build cache using all available CPU cores. |

---

## Maven Daemon (mvnd)

**Version:** 2.0.0-rc-3
**Bundles:** Maven 4

| Binary | Location | Description |
|---|---|---|
| `mvnd` | `/usr/local/bin/mvnd` (symlink) | Maven Daemon CLI. |
| `bin/mvndw` | `<project>/bin/mvndw` | Project-local wrapper; auto-downloads `mvnd` on first use. |
| `./mvnw` | `<project>/mvnw` | Standard Maven Wrapper (fallback). |

### Daemon Configuration

**File:** `.mvn/daemon.properties`

| Property | Default | Description |
|---|---|---|
| `mvnd.threads` | CPU count | Number of concurrent build threads. |
| `mvnd.minHeapSize` | `128m` | Daemon JVM minimum heap. |
| `mvnd.maxHeapSize` | `1g` | Daemon JVM maximum heap. |
| `mvnd.jvmArgs` | `--enable-preview` | Extra JVM arguments passed to all forked JVMs. |

### Proxy Configuration (required when `https_proxy` is set)

Start the proxy before building:

```bash
python3 maven-proxy-v2.py &
```

Add to `~/.m2/settings.xml`:

```xml
<settings>
  <proxies>
    <proxy>
      <id>local</id><active>true</active><protocol>https</protocol>
      <host>127.0.0.1</host><port>3128</port>
      <nonProxyHosts>localhost|127.0.0.1</nonProxyHosts>
    </proxy>
    <proxy>
      <id>local-http</id><active>true</active><protocol>http</protocol>
      <host>127.0.0.1</host><port>3128</port>
      <nonProxyHosts>localhost|127.0.0.1</nonProxyHosts>
    </proxy>
  </proxies>
</settings>
```

---

## Spotless (Google Java Format)

**Plugin:** `com.diffplug.spotless:spotless-maven-plugin`
**Style:** Google Java Format, AOSP variant (4-space indent)
**Trigger:** Runs automatically at the `compile` phase (`spotless:check`).

| Configuration item | Value |
|---|---|
| Format | Google Java Format |
| Indent style | 4 spaces (AOSP) |
| Line endings | Unix (`\n`) |
| File inclusions | `src/**/*.java` |
| Auto-apply hook | PostToolUse `.claude` hook runs `spotless:apply` after every Java file edit |

### pom.xml excerpt

```xml
<plugin>
  <groupId>com.diffplug.spotless</groupId>
  <artifactId>spotless-maven-plugin</artifactId>
  <configuration>
    <java>
      <googleJavaFormat>
        <style>AOSP</style>
      </googleJavaFormat>
    </java>
  </configuration>
  <executions>
    <execution>
      <goals><goal>check</goal></goals>
      <phase>compile</phase>
    </execution>
  </executions>
</plugin>
```

---

## maven-surefire-plugin (Unit Tests)

**Test pattern:** `**/*Test.java`
**Provider:** JUnit 5 (`junit-platform`)
**Phase:** `test`

| Configuration item | Value |
|---|---|
| JVM arg | `--enable-preview` |
| Parallel execution | Yes — dynamic strategy, `CONCURRENT` mode for both classes and methods |
| Configuration file | `src/test/resources/junit-platform.properties` |
| Single test | `-Dtest=ClassName` |
| Skip | `-DskipTests` |

### junit-platform.properties

```properties
junit.jupiter.execution.parallel.enabled=true
junit.jupiter.execution.parallel.mode.default=concurrent
junit.jupiter.execution.parallel.mode.classes.default=concurrent
junit.jupiter.execution.parallel.config.strategy=dynamic
```

### pom.xml excerpt

```xml
<plugin>
  <groupId>org.apache.maven.plugins</groupId>
  <artifactId>maven-surefire-plugin</artifactId>
  <configuration>
    <argLine>--enable-preview</argLine>
  </configuration>
</plugin>
```

---

## maven-failsafe-plugin (Integration Tests)

**Test pattern:** `**/*IT.java`
**Phase:** `integration-test` + `verify`
**Lifecycle:** Failsafe binds to `pre-integration-test`, `integration-test`, `post-integration-test`, and `verify`.

| Configuration item | Value |
|---|---|
| JVM arg | `--enable-preview` |
| Single IT | `-Dit.test=ClassName` |
| Skip | `-DskipITs` |
| Skip all tests | `-DskipTests` |

### pom.xml excerpt

```xml
<plugin>
  <groupId>org.apache.maven.plugins</groupId>
  <artifactId>maven-failsafe-plugin</artifactId>
  <configuration>
    <argLine>--enable-preview</argLine>
  </configuration>
  <executions>
    <execution>
      <goals>
        <goal>integration-test</goal>
        <goal>verify</goal>
      </goals>
    </execution>
  </executions>
</plugin>
```

---

## JPMS module-info.java

**File:** `src/main/java/module-info.java`

```java
module org.acme {
    exports org.acme;
    exports org.acme.dogfood;
    exports org.acme.dogfood.innovation;

    requires java.base;
}
```

### Directive Reference

| Directive | Syntax | Description |
|---|---|---|
| `exports` | `exports <package>;` | Makes `<package>` readable by all other modules. |
| `exports ... to` | `exports <package> to <module>;` | Qualified export — readable only by the named module. |
| `opens` | `opens <package>;` | Allows deep reflection into `<package>` at runtime. |
| `opens ... to` | `opens <package> to <module>;` | Qualified open — reflection permitted only by the named module. |
| `requires` | `requires <module>;` | Declares a compile-time and runtime dependency on `<module>`. |
| `requires transitive` | `requires transitive <module>;` | Reads `<module>` and re-exports it to dependents. |
| `requires static` | `requires static <module>;` | Compile-time-only dependency (optional at runtime). |
| `uses` | `uses <interface>;` | Declares this module as a consumer of the named service (SPI). |
| `provides` | `provides <interface> with <impl>;` | Declares this module as a provider of the named service. |

### Compiler flags for Java 26 preview features

```xml
<compilerArgs>
  <arg>--enable-preview</arg>
  <arg>-source</arg>
  <arg>26</arg>
</compilerArgs>
```

Runtime flag (Surefire and Failsafe `argLine`):

```
--enable-preview
```

---

## Test Libraries

| Library | Dependency coordinates | Version | Use |
|---|---|---|---|
| JUnit 5 | `org.junit.jupiter:junit-jupiter` | 5.x | Unit and integration tests |
| AssertJ | `org.assertj:assertj-core` | 3.x | Fluent assertions via `implements WithAssertions` |
| jqwik | `net.jqwik:jqwik` | 1.x | Property-based testing (`@Property`, `@ForAll`) |
| Instancio | `org.instancio:instancio-junit` | 3.x | Test data generation |
| ArchUnit | `com.tngtech.archunit:archunit-junit5` | 1.x | Architecture rules (`@ArchTest`) |
| Awaitility | `org.awaitility:awaitility` | 4.x | Async condition assertions |

---

## Build Lifecycle Summary

```
clean → validate → compile (+ spotless:check) → test → package → verify
                                                              │
                                          integration-test ──┘
                                          (failsafe: pre-IT, IT, post-IT, verify)
```

| Phase | Plugins invoked |
|---|---|
| `compile` | `maven-compiler-plugin`, `spotless:check` |
| `test` | `maven-surefire-plugin` |
| `package` | `maven-jar-plugin` (or `maven-shade-plugin` with `-Dshade`) |
| `pre-integration-test` | `maven-failsafe-plugin:pre-integration-test` |
| `integration-test` | `maven-failsafe-plugin:integration-test` |
| `post-integration-test` | `maven-failsafe-plugin:post-integration-test` |
| `verify` | `maven-failsafe-plugin:verify` |

---

## JDK Requirements

| Requirement | Value |
|---|---|
| Target JDK | GraalVM Community CE 25.0.2 (Java 26 EA) |
| `--release` | `26` |
| `--enable-preview` | Required at compile, test, and runtime |
| `JAVA_HOME` | Must point to GraalVM CE 25.0.2 installation |

Verify the active JDK:

```bash
java -version
# Expected: java version "26-ea" ... GraalVM CE 25.0.2
```
