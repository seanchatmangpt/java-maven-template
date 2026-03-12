package io.github.seanchatmangpt.jotp.dogfood.core;

import java.util.Objects;

/**
 * Dogfood: rendered from templates/java/core/record.tera
 *
 * <p>Demonstrates record with compact constructor validation and builder pattern.
 */
public record Person(
        String name,
        int age
) {

    /**
     * Compact constructor — validates all components.
     */
    public Person {
        Objects.requireNonNull(name, "name must not be null");
        if (name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        if (age < 0 || age > 150) {
            throw new IllegalArgumentException("age must be between 0 and 150, got " + age);
        }
    }

    /**
     * Creates a new builder for {@code Person}.
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Step builder for {@code Person}.
     */
    public static final class Builder {
        private String name;
        private int age;

        private Builder() {}

        public Builder name(String name) {
            this.name = name;
            return this;
        }

        public Builder age(int age) {
            this.age = age;
            return this;
        }

        public Person build() {
            return new Person(name, age);
        }
    }
}
