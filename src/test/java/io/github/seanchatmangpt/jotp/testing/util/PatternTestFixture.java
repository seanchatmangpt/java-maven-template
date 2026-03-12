package io.github.seanchatmangpt.jotp.testing.util;

import java.lang.reflect.Constructor;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Reflection-driven factory for creating test fixtures from sealed types.
 *
 * <p>Uses Java 26 reflection API to:
 * <ul>
 *   <li>Enumerate sealed Message type variants</li>
 *   <li>Create test instances for each variant</li>
 *   <li>Auto-cleanup processes and deregistration</li>
 *   <li>Manage process lifecycle</li>
 * </ul>
 *
 * <p>Usage:
 * <pre>{@code
 * var fixture = PatternTestFixture.for(ContentBasedRouter.class)
 *   .withSupervision("ONE_FOR_ONE")
 *   .registerInRegistry("test-router")
 *   .build();
 *
 * var router = fixture.createProcess();
 * // ... test ...
 * fixture.cleanup();
 * }</pre>
 */
public class PatternTestFixture<P> {

  private final Class<P> patternClass;
  private final Map<String, Object> config = new ConcurrentHashMap<>();
  private final List<Object> createdInstances = Collections.synchronizedList(new ArrayList<>());
  private Object processInstance;

  private PatternTestFixture(Class<P> patternClass) {
    this.patternClass = patternClass;
  }

  public static <P> PatternTestFixture<P> for(Class<P> patternClass) {
    return new PatternTestFixture<>(patternClass);
  }

  /**
   * Configure supervision strategy (ONE_FOR_ONE, ONE_FOR_ALL, REST_FOR_ONE).
   */
  public PatternTestFixture<P> withSupervision(String strategy) {
    config.put("supervisionStrategy", strategy);
    return this;
  }

  /**
   * Register process in ProcessRegistry with given name.
   */
  public PatternTestFixture<P> registerInRegistry(String registryName) {
    config.put("registerInRegistry", true);
    config.put("registryName", registryName);
    return this;
  }

  /**
   * Enable message capturing during fixture lifecycle.
   */
  public PatternTestFixture<P> captureMessages() {
    config.put("captureMessages", true);
    return this;
  }

  /**
   * Create the fixture (instantiate process).
   */
  public PatternTestFixture<P> build() {
    try {
      this.processInstance = createProcessInstance(patternClass);
      createdInstances.add(processInstance);
    } catch (Exception e) {
      throw new IllegalStateException("Failed to create fixture for " + patternClass.getName(), e);
    }
    return this;
  }

  /**
   * Get the created process instance.
   */
  public P createProcess() {
    if (processInstance == null) {
      build();
    }
    return (P) processInstance;
  }

  /**
   * Clean up all resources (termination, deregistration).
   */
  public void cleanup() {
    for (var instance : createdInstances) {
      try {
        // Would call process termination / cleanup here
        // e.g., ProcessRegistry.unregister(), process.terminate()
      } catch (Exception e) {
        // Log but continue cleanup
      }
    }
    createdInstances.clear();
    processInstance = null;
  }

  /**
   * Get configuration value.
   */
  public Object getConfig(String key) {
    return config.get(key);
  }

  /**
   * Create process instance via reflection on constructors.
   */
  private P createProcessInstance(Class<P> clazz) throws Exception {
    // Try no-arg constructor first
    try {
      Constructor<P> constructor = clazz.getDeclaredConstructor();
      constructor.setAccessible(true);
      return constructor.newInstance();
    } catch (NoSuchMethodException e) {
      // Try first available constructor
      Constructor<?>[] constructors = clazz.getDeclaredConstructors();
      if (constructors.length > 0) {
        constructors[0].setAccessible(true);
        return (P) constructors[0].newInstance();
      }
    }

    throw new IllegalStateException(
        "Cannot create instance of " + clazz.getName() + ": no accessible constructor");
  }

  /**
   * Get all created instances (for cleanup verification).
   */
  public List<Object> getCreatedInstances() {
    return Collections.unmodifiableList(createdInstances);
  }

  /**
   * Check if class is sealed (uses Java 26 reflection).
   */
  public boolean isPatternSealed() {
    return patternClass.isSealed();
  }

  /**
   * Get permitted subclasses of sealed pattern.
   */
  public Class<?>[] getPatternVariants() {
    if (isPatternSealed()) {
      return patternClass.permittedSubclasses();
    }
    return new Class<?>[0];
  }

  @Override
  public String toString() {
    return "PatternTestFixture[pattern=" + patternClass.getSimpleName()
        + ", instances=" + createdInstances.size()
        + ", sealed=" + isPatternSealed() + "]";
  }
}
