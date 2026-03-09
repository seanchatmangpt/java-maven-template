package org.acme.dogfood.patterns;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.acme.Result;

/**
 * Dogfood: rendered from templates/java/patterns/service-layer.tera
 *
 * <p>Service layer coordinating {@link PersonRepository} — demonstrates the Service Layer pattern
 * with railway-oriented error handling via {@link Result}.
 *
 * <p>BEFORE (legacy): checked exceptions or null-returning methods for error cases.
 * AFTER (modern): {@code Result<T, String>} makes success/failure explicit in the type system;
 * callers use {@code fold}, {@code map}, or {@code recover} instead of try/catch.
 */
public final class PersonService {

    private final PersonRepository repository;

    public PersonService(PersonRepository repository) {
        this.repository = Objects.requireNonNull(repository, "repository must not be null");
    }

    /**
     * Create and persist a new person. Returns {@link Result.Failure} if validation fails
     * or a person with the same id already exists.
     */
    public Result<PersonRepository.PersonRecord, String> createPerson(
            String id, String name, int age) {
        if (id == null || id.isBlank()) return Result.failure("id must not be blank");
        if (name == null || name.isBlank()) return Result.failure("name must not be blank");
        if (age < 0 || age > 150) return Result.failure("age must be 0-150, got " + age);
        if (repository.findById(id).isPresent())
            return Result.failure("Person already exists: " + id);

        var person = new PersonRepository.PersonRecord(id, name, age);
        repository.save(person);
        return Result.success(person);
    }

    /**
     * Find a person by id. Returns empty if not found (not a failure — absence is valid).
     */
    public Optional<PersonRepository.PersonRecord> findById(String id) {
        return repository.findById(Objects.requireNonNull(id));
    }

    /** All stored persons. */
    public List<PersonRepository.PersonRecord> findAll() {
        return repository.findAll();
    }

    /**
     * Delete a person by id. Returns {@link Result.Failure} if no such person exists.
     */
    public Result<Boolean, String> deletePerson(String id) {
        if (!repository.deleteById(Objects.requireNonNull(id)))
            return Result.failure("Person not found: " + id);
        return Result.success(true);
    }

    /** Count of stored persons. */
    public int count() {
        return repository.count();
    }
}
