# Result<T,E> — API Reference

**Package:** `org.acme`
**Type:** `sealed interface Result<T, E>`
**Permitted subclasses:** `Result.Success<T,E>`, `Result.Failure<T,E>`
**Pattern:** Railway-oriented programming

---

## Overview

`Result<T,E>` is a sealed interface representing either a successful value of type `T` or a failure value of type `E`. It is used in place of checked exceptions for operations that may fail in a predictable, typed way.

```java
public sealed interface Result<T, E>
    permits Result.Success, Result.Failure {

    record Success<T, E>(T value) implements Result<T, E> {}
    record Failure<T, E>(E error) implements Result<T, E> {}
}
```

---

## Factory Methods

```java
public sealed interface Result<T, E> {

    static <T, E> Result<T, E> success(T value);

    static <T, E> Result<T, E> failure(E error);

    static <T, E> Result<T, E> of(Supplier<T> supplier);
}
```

| Method | Parameters | Returns | Description |
|---|---|---|---|
| `Result.success(value)` | `T value` | `Result<T,E>` as `Success<T,E>` | Wraps `value` in a `Success`. |
| `Result.failure(error)` | `E error` | `Result<T,E>` as `Failure<T,E>` | Wraps `error` in a `Failure`. |
| `Result.of(supplier)` | `Supplier<T> supplier` — zero-arg callable that may throw | `Result<T,E>` | Invokes `supplier.get()`. Returns `Success` if it returns normally; returns `Failure` wrapping the `Throwable` as the error if it throws. The `E` type parameter becomes `Throwable` in this case. |

### `Result.of` type inference

```java
// E inferred as Throwable
Result<Integer, Throwable> r = Result.of(() -> Integer.parseInt(input));
```

---

## Inspection Methods

```java
boolean isSuccess();

boolean isFailure();

T orElseThrow();

T orElseThrow(Function<E, ? extends RuntimeException> mapper);
```

| Method | Returns | Throws | Description |
|---|---|---|---|
| `isSuccess()` | `boolean` | — | `true` if this is a `Success`. |
| `isFailure()` | `boolean` | — | `true` if this is a `Failure`. |
| `orElseThrow()` | `T` | `NoSuchElementException` if `Failure` | Returns the success value or throws. |
| `orElseThrow(mapper)` | `T` | The exception returned by `mapper` if `Failure` | Returns the success value or throws the result of applying `mapper` to the error. |

---

## Transformation Methods

```java
<U> Result<U, E> map(Function<T, U> fn);

<U> Result<U, E> flatMap(Function<T, Result<U, E>> fn);

<U> U fold(Function<T, U> onSuccess, Function<E, U> onFailure);

Result<T, E> recover(Function<E, T> fn);

Result<T, E> peek(Consumer<T> fn);
```

| Method | Parameters | Returns | Description |
|---|---|---|---|
| `map(fn)` | `Function<T,U> fn` | `Result<U,E>` | If `Success`, applies `fn` to the value and returns a new `Success<U,E>`. If `Failure`, returns the same `Failure` unchanged. |
| `flatMap(fn)` | `Function<T,Result<U,E>> fn` | `Result<U,E>` | If `Success`, applies `fn` to the value and returns the `Result` it produces. If `Failure`, returns the same `Failure` unchanged. Used to chain fallible operations. |
| `fold(onSuccess, onFailure)` | `Function<T,U> onSuccess`, `Function<E,U> onFailure` | `U` | Applies `onSuccess` if `Success`, `onFailure` if `Failure`. Always returns a value; never throws. |
| `recover(fn)` | `Function<E,T> fn` | `Result<T,E>` | If `Failure`, applies `fn` to the error and returns a new `Success` wrapping the result. If `Success`, returns `this` unchanged. |
| `peek(fn)` | `Consumer<T> fn` | `Result<T,E>` | If `Success`, runs `fn` as a side effect and returns `this` unchanged. If `Failure`, does nothing. |

---

## Method Behaviour by Variant

| Method | On `Success<T,E>` | On `Failure<T,E>` |
|---|---|---|
| `isSuccess()` | `true` | `false` |
| `isFailure()` | `false` | `true` |
| `orElseThrow()` | Returns `value` | Throws `NoSuchElementException` |
| `orElseThrow(mapper)` | Returns `value` | Throws `mapper.apply(error)` |
| `map(fn)` | `Success(fn.apply(value))` | `this` (unchanged `Failure`) |
| `flatMap(fn)` | `fn.apply(value)` | `this` (unchanged `Failure`) |
| `fold(s, f)` | `s.apply(value)` | `f.apply(error)` |
| `recover(fn)` | `this` (unchanged `Success`) | `Success(fn.apply(error))` |
| `peek(fn)` | Runs `fn`, returns `this` | Returns `this` |

---

## Pattern Matching

`Result` is a sealed interface with two permitted records. Use pattern matching in `switch` expressions:

```java
String display = switch (result) {
    case Result.Success<String, Throwable>(var v) -> "OK: " + v;
    case Result.Failure<String, Throwable>(var e) -> "ERR: " + e.getMessage();
};
```

Deconstruction patterns work because `Success` and `Failure` are records.

---

## Chaining Example

```java
Result<Integer, Throwable> parsed   = Result.of(() -> Integer.parseInt(raw));
Result<Double,  Throwable> divided  = parsed.flatMap(n -> n == 0
    ? Result.failure(new ArithmeticException("zero"))
    : Result.success(100.0 / n));
Result<String,  Throwable> rendered = divided.map("%.2f"::formatted);
String output = rendered.fold(
    v -> "Result: " + v,
    e -> "Error: "  + e.getMessage()
);
```

---

## Interoperability

### Converting from Optional

```java
Result<T, String> fromOptional = optional
    .<Result<T, String>>map(Result::success)
    .orElseGet(() -> Result.failure("value absent"));
```

### Converting to Optional

```java
Optional<T> toOptional = result.isSuccess()
    ? Optional.of(result.orElseThrow())
    : Optional.empty();
```

### Converting from CompletableFuture

```java
Result<T, Throwable> fromFuture = Result.of(() -> future.join());
```

---

## Thread Safety

`Result` instances are immutable records. All methods are safe to call from multiple threads without synchronization.

---

## Dogfood Examples

| File | Template | Demonstrates |
|---|---|---|
| `org.acme.dogfood.ResultRailway` | `error-handling/result-railway` | Chained `map`, `flatMap`, `recover`, `fold` |
| `org.acme.dogfood.ResultRailwayTest` | `testing/junit5-test` + `testing/assertj-assertions` | `isSuccess()`, `orElseThrow()`, `fold()` assertions |
