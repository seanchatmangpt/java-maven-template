package io.github.seanchatmangpt.jotp.dogfood.security;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * Dogfood: rendered from templates/java/security/input-validation.tera
 *
 * <p>Input validation patterns — centralized in constructors with error accumulation.
 */
public final class InputValidation {

    private InputValidation() {}

    // Precondition checks
    public static final class Preconditions {

        private Preconditions() {}

        public static String requireNonBlank(String value, String name) {
            Objects.requireNonNull(value, name + " must not be null");
            if (value.isBlank()) {
                throw new IllegalArgumentException(name + " must not be blank");
            }
            return value;
        }

        public static int requireInRange(int value, int min, int max, String name) {
            if (value < min || value > max) {
                throw new IllegalArgumentException(
                        "%s must be between %d and %d, got %d".formatted(name, min, max, value));
            }
            return value;
        }

        public static int requirePositive(int value, String name) {
            if (value <= 0) {
                throw new IllegalArgumentException(name + " must be positive, got " + value);
            }
            return value;
        }

        public static long requireNonNegative(long value, String name) {
            if (value < 0) {
                throw new IllegalArgumentException(name + " must be non-negative, got " + value);
            }
            return value;
        }

        public static String requireMatches(String value, Pattern pattern, String name) {
            Objects.requireNonNull(value, name + " must not be null");
            if (!pattern.matcher(value).matches()) {
                throw new IllegalArgumentException(
                        name + " must match pattern " + pattern.pattern());
            }
            return value;
        }

        public static <T> List<T> requireNonEmpty(List<T> list, String name) {
            Objects.requireNonNull(list, name + " must not be null");
            if (list.isEmpty()) {
                throw new IllegalArgumentException(name + " must not be empty");
            }
            return list;
        }
    }

    // Validated value objects

    /** A non-blank, trimmed string value. */
    public record NonBlankString(String value) {
        public NonBlankString {
            Objects.requireNonNull(value, "value must not be null");
            value = value.strip();
            if (value.isEmpty()) {
                throw new IllegalArgumentException("value must not be blank");
            }
        }
    }

    private static final Pattern EMAIL_PATTERN =
            Pattern.compile("^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$");

    /** A validated email address. */
    public record Email(String value) {
        public Email {
            Preconditions.requireNonBlank(value, "email");
            value = value.strip().toLowerCase(java.util.Locale.ROOT);
            Preconditions.requireMatches(value, EMAIL_PATTERN, "email");
        }
    }

    /** A validated age (0-150). */
    public record Age(int value) {
        public Age {
            Preconditions.requireInRange(value, 0, 150, "age");
        }
    }

    /** A positive, finite amount. */
    public record PositiveAmount(double value) {
        public PositiveAmount {
            if (value <= 0) {
                throw new IllegalArgumentException("Amount must be positive: " + value);
            }
            if (Double.isNaN(value) || Double.isInfinite(value)) {
                throw new IllegalArgumentException("Amount must be finite: " + value);
            }
        }
    }

    /** A fully validated user. */
    public record User(NonBlankString name, Email email, Age age, List<String> roles) {
        public User {
            Objects.requireNonNull(name, "name must not be null");
            Objects.requireNonNull(email, "email must not be null");
            Objects.requireNonNull(age, "age must not be null");
            roles = roles != null ? List.copyOf(roles) : List.of();
        }
    }

    // Validation error accumulation

    public record ValidationError(String field, String message) {
        public ValidationError {
            Preconditions.requireNonBlank(field, "field");
            Preconditions.requireNonBlank(message, "message");
        }

        @Override
        public String toString() {
            return field + ": " + message;
        }
    }

    public static final class Validator<T> {

        private final List<ValidationError> errors = new ArrayList<>();

        private Validator() {}

        public static <T> Validator<T> of() {
            return new Validator<>();
        }

        public Validator<T> check(String field, boolean condition, String message) {
            if (!condition) {
                errors.add(new ValidationError(field, message));
            }
            return this;
        }

        public <V> Validator<T> check(
                String field, V value, Predicate<V> predicate, String message) {
            if (!predicate.test(value)) {
                errors.add(new ValidationError(field, message));
            }
            return this;
        }

        public Validator<T> requireNonNull(String field, Object value) {
            if (value == null) {
                errors.add(new ValidationError(field, "must not be null"));
            }
            return this;
        }

        public Validator<T> requireNonBlank(String field, String value) {
            if (value == null || value.isBlank()) {
                errors.add(new ValidationError(field, "must not be blank"));
            }
            return this;
        }

        public ValidationResult<T> validate(java.util.function.Supplier<T> supplier) {
            if (errors.isEmpty()) {
                return new ValidationResult.Valid<>(supplier.get());
            }
            return new ValidationResult.Invalid<>(Collections.unmodifiableList(errors));
        }

        public T validateOrThrow(java.util.function.Supplier<T> supplier) {
            if (!errors.isEmpty()) {
                throw new IllegalArgumentException(
                        "Validation failed: "
                                + errors.stream()
                                        .map(ValidationError::toString)
                                        .collect(
                                                java.util.stream.Collectors.joining("; ")));
            }
            return supplier.get();
        }

        public List<ValidationError> errors() {
            return Collections.unmodifiableList(errors);
        }
    }

    /** Result of validation — either Valid or Invalid with errors. */
    public sealed interface ValidationResult<T> {

        boolean isValid();

        <U> ValidationResult<U> map(Function<T, U> mapper);

        <U> U fold(
                Function<T, U> onValid, Function<List<ValidationError>, U> onInvalid);

        record Valid<T>(T value) implements ValidationResult<T> {
            @Override
            public boolean isValid() {
                return true;
            }

            @Override
            public <U> ValidationResult<U> map(Function<T, U> mapper) {
                return new Valid<>(mapper.apply(value));
            }

            @Override
            public <U> U fold(
                    Function<T, U> onValid,
                    Function<List<ValidationError>, U> onInvalid) {
                return onValid.apply(value);
            }
        }

        record Invalid<T>(List<ValidationError> errors) implements ValidationResult<T> {
            public Invalid {
                errors = List.copyOf(errors);
            }

            @Override
            public boolean isValid() {
                return false;
            }

            @Override
            @SuppressWarnings("unchecked")
            public <U> ValidationResult<U> map(Function<T, U> mapper) {
                return (ValidationResult<U>) this;
            }

            @Override
            public <U> U fold(
                    Function<T, U> onValid,
                    Function<List<ValidationError>, U> onInvalid) {
                return onInvalid.apply(errors);
            }
        }
    }

    /** Validates a registration request with error accumulation. */
    public static ValidationResult<User> validateRegistration(
            String name, String email, int age) {
        return Validator.<User>of()
                .requireNonBlank("name", name)
                .check(
                        "email",
                        email,
                        e -> e != null && EMAIL_PATTERN.matcher(e).matches(),
                        "must be a valid email address")
                .check("age", age >= 0 && age <= 150, "must be between 0 and 150")
                .validate(
                        () ->
                                new User(
                                        new NonBlankString(name),
                                        new Email(email),
                                        new Age(age),
                                        List.of()));
    }
}
