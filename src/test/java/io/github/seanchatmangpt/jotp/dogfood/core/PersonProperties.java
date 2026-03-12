package io.github.seanchatmangpt.jotp.dogfood.core;

import java.util.List;

import org.assertj.core.api.WithAssertions;

import net.jqwik.api.*;
import net.jqwik.api.constraints.*;

/**
 * Dogfood: rendered from templates/java/testing/property-based-jqwik.tera
 *
 * <p>Property-based tests for the Person record.
 */
class PersonProperties implements WithAssertions {

    // Basic property: valid names and ages always produce a valid Person
    @Property(tries = 1000)
    void validInputsAlwaysCreatePerson(
            @ForAll @StringLength(min = 1, max = 100) @CharRange(from = 'a', to = 'z')
                    String name,
            @ForAll @IntRange(min = 0, max = 150) int age) {
        var person = new Person(name, age);
        assertThat(person.name()).isEqualTo(name);
        assertThat(person.age()).isEqualTo(age);
    }

    // Record equality: same values produce equal records
    @Property
    void recordEqualityIsSymmetric(
            @ForAll @StringLength(min = 1, max = 50) @CharRange(from = 'a', to = 'z')
                    String name,
            @ForAll @IntRange(min = 0, max = 150) int age) {
        var a = new Person(name, age);
        var b = new Person(name, age);
        assertThat(a).isEqualTo(b);
        assertThat(b).isEqualTo(a);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    // Builder produces same result as constructor
    @Property
    void builderMatchesConstructor(
            @ForAll @StringLength(min = 1, max = 50) @CharRange(from = 'a', to = 'z')
                    String name,
            @ForAll @IntRange(min = 0, max = 150) int age) {
        var fromConstructor = new Person(name, age);
        var fromBuilder = Person.builder().name(name).age(age).build();
        assertThat(fromBuilder).isEqualTo(fromConstructor);
    }

    // Custom arbitrary for Person
    @Property
    void customArbitraryProducesValidPeople(@ForAll("validPeople") Person person) {
        assertThat(person.name()).isNotBlank();
        assertThat(person.age()).isBetween(0, 150);
    }

    @Provide
    Arbitrary<Person> validPeople() {
        var names =
                Arbitraries.strings()
                        .withCharRange('a', 'z')
                        .ofMinLength(1)
                        .ofMaxLength(50);
        var ages = Arbitraries.integers().between(0, 150);
        return Combinators.combine(names, ages).as(Person::new);
    }
}
