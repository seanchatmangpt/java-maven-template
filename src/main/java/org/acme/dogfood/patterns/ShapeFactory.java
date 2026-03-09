package org.acme.dogfood.patterns;

/**
 * Dogfood: rendered from templates/java/patterns/factory-sealed.tera
 *
 * <p>Shape factory using sealed interfaces and records — demonstrates the modern Java approach to
 * the Factory + Visitor patterns using exhaustive {@code switch} expressions.
 *
 * <p>BEFORE (legacy): abstract class hierarchy with {@code instanceof} chains.
 * AFTER (modern): sealed interface permits exactly the known shapes; switch is exhaustive,
 * compiler-verified.
 */
public final class ShapeFactory {

    private ShapeFactory() {}

    /** Sealed shape hierarchy — all variants known at compile time. */
    public sealed interface Shape permits Shape.Circle, Shape.Rectangle, Shape.Triangle {

        double area();

        double perimeter();

        record Circle(double radius) implements Shape {

            public Circle {
                if (radius <= 0)
                    throw new IllegalArgumentException(
                            "radius must be positive, got " + radius);
            }

            @Override
            public double area() {
                return Math.PI * radius * radius;
            }

            @Override
            public double perimeter() {
                return 2 * Math.PI * radius;
            }
        }

        record Rectangle(double width, double height) implements Shape {

            public Rectangle {
                if (width <= 0 || height <= 0)
                    throw new IllegalArgumentException(
                            "dimensions must be positive, got width=%s height=%s"
                                    .formatted(width, height));
            }

            @Override
            public double area() {
                return width * height;
            }

            @Override
            public double perimeter() {
                return 2 * (width + height);
            }
        }

        record Triangle(double a, double b, double c) implements Shape {

            public Triangle {
                if (a <= 0 || b <= 0 || c <= 0)
                    throw new IllegalArgumentException("sides must be positive");
                if (a + b <= c || b + c <= a || a + c <= b)
                    throw new IllegalArgumentException(
                            "invalid triangle: sides %s, %s, %s violate triangle inequality"
                                    .formatted(a, b, c));
            }

            @Override
            public double area() {
                double s = (a + b + c) / 2.0;
                return Math.sqrt(s * (s - a) * (s - b) * (s - c));
            }

            @Override
            public double perimeter() {
                return a + b + c;
            }
        }
    }

    /** Create a circle. */
    public static Shape circle(double radius) {
        return new Shape.Circle(radius);
    }

    /** Create a rectangle. */
    public static Shape rectangle(double width, double height) {
        return new Shape.Rectangle(width, height);
    }

    /** Create a triangle. */
    public static Shape triangle(double a, double b, double c) {
        return new Shape.Triangle(a, b, c);
    }

    /**
     * Exhaustive pattern-match description — compiler enforces that all variants are handled.
     *
     * <p>BEFORE (legacy): {@code if (shape instanceof Circle)} chains.
     * AFTER (modern): sealed switch with record deconstruction.
     */
    public static String describe(Shape shape) {
        return switch (shape) {
            case Shape.Circle(var r) -> "Circle with radius %.2f".formatted(r);
            case Shape.Rectangle(var w, var h) -> "Rectangle %.2f × %.2f".formatted(w, h);
            case Shape.Triangle(var a, var b, var c) ->
                    "Triangle with sides %.2f, %.2f, %.2f".formatted(a, b, c);
        };
    }
}
