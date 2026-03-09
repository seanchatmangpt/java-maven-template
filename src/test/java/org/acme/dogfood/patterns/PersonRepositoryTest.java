package org.acme.dogfood.patterns;

import org.assertj.core.api.WithAssertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Tests for {@link PersonRepository} — the repository-generic template dogfood. */
class PersonRepositoryTest implements WithAssertions {

    private PersonRepository repo;

    @BeforeEach
    void setUp() {
        repo = new PersonRepository();
    }

    @Test
    void save_thenFindById_returnsRecord() {
        var person = new PersonRepository.PersonRecord("1", "Alice", 30);
        repo.save(person);
        assertThat(repo.findById("1")).contains(person);
    }

    @Test
    void findById_unknown_returnsEmpty() {
        assertThat(repo.findById("nobody")).isEmpty();
    }

    @Test
    void findAll_returnsAllSaved() {
        repo.save(new PersonRepository.PersonRecord("1", "Alice", 30));
        repo.save(new PersonRepository.PersonRecord("2", "Bob", 25));
        assertThat(repo.findAll()).hasSize(2);
    }

    @Test
    void deleteById_existing_returnsTrueAndRemoves() {
        repo.save(new PersonRepository.PersonRecord("1", "Alice", 30));
        assertThat(repo.deleteById("1")).isTrue();
        assertThat(repo.findById("1")).isEmpty();
    }

    @Test
    void deleteById_nonExisting_returnsFalse() {
        assertThat(repo.deleteById("ghost")).isFalse();
    }

    @Test
    void count_reflectsStoredRecords() {
        assertThat(repo.count()).isZero();
        repo.save(new PersonRepository.PersonRecord("1", "Alice", 30));
        assertThat(repo.count()).isOne();
    }

    @Test
    void save_overwritesExistingRecord() {
        repo.save(new PersonRepository.PersonRecord("1", "Alice", 30));
        repo.save(new PersonRepository.PersonRecord("1", "Alice Updated", 31));
        assertThat(repo.count()).isOne();
        assertThat(repo.findById("1").get().name()).isEqualTo("Alice Updated");
    }

    @Test
    void personRecord_rejectsInvalidAge() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new PersonRepository.PersonRecord("1", "Bad", -1));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new PersonRepository.PersonRecord("1", "Bad", 151));
    }
}
