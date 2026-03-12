package io.github.seanchatmangpt.jotp.dogfood.core;

import org.assertj.core.api.WithAssertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Dogfood: rendered from templates/java/testing/junit5-test.tera
 *
 * <p>Tests for the Person record generated from core/record.tera.
 */
@DisplayName("Person")
class PersonTest implements WithAssertions {

    private Person subject;

    @BeforeEach
    void setUp() {
        subject = new Person("Alice", 30);
    }

    @Test
    @DisplayName("should create a valid person")
    void shouldCreateValidPerson() {
        assertThat(subject.name()).isEqualTo("Alice");
        assertThat(subject.age()).isEqualTo(30);
    }

    @Test
    @DisplayName("should support builder pattern")
    void shouldSupportBuilder() {
        var person = Person.builder().name("Bob").age(25).build();

        assertThat(person.name()).isEqualTo("Bob");
        assertThat(person.age()).isEqualTo(25);
    }

    @Test
    @DisplayName("should reject null name")
    void shouldRejectNullName() {
        assertThatNullPointerException().isThrownBy(() -> new Person(null, 30));
    }

    @Test
    @DisplayName("should reject blank name")
    void shouldRejectBlankName() {
        assertThatIllegalArgumentException().isThrownBy(() -> new Person("  ", 30));
    }

    @Test
    @DisplayName("should reject negative age")
    void shouldRejectNegativeAge() {
        assertThatIllegalArgumentException().isThrownBy(() -> new Person("Alice", -1));
    }

    @Test
    @DisplayName("should reject age over 150")
    void shouldRejectAgeOver150() {
        assertThatIllegalArgumentException().isThrownBy(() -> new Person("Alice", 151));
    }

    @Nested
    @DisplayName("equality")
    class Equality {

        @Test
        @DisplayName("records with same values are equal")
        void recordsWithSameValuesAreEqual() {
            var a = new Person("Alice", 30);
            var b = new Person("Alice", 30);
            assertThat(a).isEqualTo(b);
            assertThat(a.hashCode()).isEqualTo(b.hashCode());
        }

        @Test
        @DisplayName("records with different values are not equal")
        void recordsWithDifferentValuesAreNotEqual() {
            var a = new Person("Alice", 30);
            var b = new Person("Bob", 25);
            assertThat(a).isNotEqualTo(b);
        }
    }
}
