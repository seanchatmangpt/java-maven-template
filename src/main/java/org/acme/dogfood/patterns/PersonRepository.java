package org.acme.dogfood.patterns;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Dogfood: rendered from templates/java/patterns/repository-generic.tera
 *
 * <p>Generic repository pattern for {@code Person} entities backed by an in-memory
 * {@link ConcurrentHashMap}. Demonstrates the repository pattern using modern Java records
 * for entities, Optional for nullable returns, and List.copyOf for defensive copies.
 *
 * <p>BEFORE (legacy): DAO class with checked exceptions and manual null handling.
 * AFTER (modern): repository returning Optional and defensive-copy collections.
 */
public final class PersonRepository {

    /** Entity managed by this repository. */
    public record PersonRecord(String id, String name, int age) {

        public PersonRecord {
            Objects.requireNonNull(id, "id must not be null");
            Objects.requireNonNull(name, "name must not be null");
            if (id.isBlank()) throw new IllegalArgumentException("id must not be blank");
            if (name.isBlank()) throw new IllegalArgumentException("name must not be blank");
            if (age < 0 || age > 150)
                throw new IllegalArgumentException("age must be 0-150, got " + age);
        }
    }

    private final Map<String, PersonRecord> store = new ConcurrentHashMap<>();

    /** Persist or replace a person record. */
    public void save(PersonRecord person) {
        Objects.requireNonNull(person, "person must not be null");
        store.put(person.id(), person);
    }

    /** Look up a person by id; returns empty if not found. */
    public Optional<PersonRecord> findById(String id) {
        return Optional.ofNullable(store.get(Objects.requireNonNull(id)));
    }

    /** Returns a defensive snapshot of all stored persons. */
    public List<PersonRecord> findAll() {
        return List.copyOf(store.values());
    }

    /** Delete a person by id. Returns {@code true} if the record existed. */
    public boolean deleteById(String id) {
        return store.remove(Objects.requireNonNull(id)) != null;
    }

    /** Total number of stored persons. */
    public int count() {
        return store.size();
    }

    /** Remove all persons — for use in tests. */
    public void clear() {
        store.clear();
    }
}
