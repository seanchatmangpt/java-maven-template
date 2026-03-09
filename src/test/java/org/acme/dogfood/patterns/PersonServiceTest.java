package org.acme.dogfood.patterns;

import org.assertj.core.api.WithAssertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Tests for {@link PersonService} — the service-layer template dogfood. */
class PersonServiceTest implements WithAssertions {

    private PersonRepository repo;
    private PersonService service;

    @BeforeEach
    void setUp() {
        repo = new PersonRepository();
        service = new PersonService(repo);
    }

    @Test
    void createPerson_valid_returnsSuccess() {
        var result = service.createPerson("1", "Alice", 30);
        assertThat(result.isSuccess()).isTrue();
        var person = result.orElseThrow();
        assertThat(person.id()).isEqualTo("1");
        assertThat(person.name()).isEqualTo("Alice");
        assertThat(person.age()).isEqualTo(30);
    }

    @Test
    void createPerson_persistsToRepository() {
        service.createPerson("1", "Alice", 30);
        assertThat(repo.findById("1")).isPresent();
    }

    @Test
    void createPerson_blankId_returnsFailure() {
        var result = service.createPerson("  ", "Alice", 30);
        assertThat(result.isFailure()).isTrue();
    }

    @Test
    void createPerson_blankName_returnsFailure() {
        var result = service.createPerson("1", "", 30);
        assertThat(result.isFailure()).isTrue();
    }

    @Test
    void createPerson_invalidAge_returnsFailure() {
        assertThat(service.createPerson("1", "Alice", -1).isFailure()).isTrue();
        assertThat(service.createPerson("2", "Alice", 200).isFailure()).isTrue();
    }

    @Test
    void createPerson_duplicate_returnsFailure() {
        service.createPerson("1", "Alice", 30);
        var result = service.createPerson("1", "Bob", 25);
        assertThat(result.isFailure()).isTrue();
        assertThat(result.fold(p -> "", e -> e)).contains("1");
    }

    @Test
    void findById_existing_returnsPresent() {
        service.createPerson("1", "Alice", 30);
        assertThat(service.findById("1")).isPresent();
    }

    @Test
    void findById_unknown_returnsEmpty() {
        assertThat(service.findById("ghost")).isEmpty();
    }

    @Test
    void findAll_returnsAll() {
        service.createPerson("1", "Alice", 30);
        service.createPerson("2", "Bob", 25);
        assertThat(service.findAll()).hasSize(2);
    }

    @Test
    void deletePerson_existing_returnsSuccessTrue() {
        service.createPerson("1", "Alice", 30);
        var result = service.deletePerson("1");
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.orElseThrow()).isTrue();
        assertThat(repo.findById("1")).isEmpty();
    }

    @Test
    void deletePerson_nonExisting_returnsFailure() {
        var result = service.deletePerson("ghost");
        assertThat(result.isFailure()).isTrue();
    }

    @Test
    void count_reflectsCreatedPersons() {
        assertThat(service.count()).isZero();
        service.createPerson("1", "Alice", 30);
        assertThat(service.count()).isOne();
    }

    @Test
    void constructor_nullRepository_throws() {
        assertThatNullPointerException().isThrownBy(() -> new PersonService(null));
    }
}
