package org.acme.dogfood.patterns;

import org.assertj.core.api.WithAssertions;
import org.junit.jupiter.api.Test;

/** Tests for {@link ShapeFactory} — the factory-sealed template dogfood. */
class ShapeFactoryTest implements WithAssertions {

    private static final double DELTA = 1e-9;

    // ── Circle ────────────────────────────────────────────────────────────────

    @Test
    void circle_area_isCorrect() {
        var circle = ShapeFactory.circle(5.0);
        assertThat(circle.area()).isCloseTo(Math.PI * 25, withinPercentage(0.001));
    }

    @Test
    void circle_perimeter_isCorrect() {
        var circle = ShapeFactory.circle(3.0);
        assertThat(circle.perimeter()).isCloseTo(2 * Math.PI * 3, withinPercentage(0.001));
    }

    @Test
    void circle_invalidRadius_throws() {
        assertThatIllegalArgumentException().isThrownBy(() -> ShapeFactory.circle(0));
        assertThatIllegalArgumentException().isThrownBy(() -> ShapeFactory.circle(-1));
    }

    // ── Rectangle ─────────────────────────────────────────────────────────────

    @Test
    void rectangle_area_isCorrect() {
        var rect = ShapeFactory.rectangle(4.0, 6.0);
        assertThat(rect.area()).isEqualTo(24.0);
    }

    @Test
    void rectangle_perimeter_isCorrect() {
        var rect = ShapeFactory.rectangle(4.0, 6.0);
        assertThat(rect.perimeter()).isEqualTo(20.0);
    }

    @Test
    void rectangle_invalidDimension_throws() {
        assertThatIllegalArgumentException().isThrownBy(() -> ShapeFactory.rectangle(0, 5));
        assertThatIllegalArgumentException().isThrownBy(() -> ShapeFactory.rectangle(5, -1));
    }

    // ── Triangle ──────────────────────────────────────────────────────────────

    @Test
    void triangle_equilateral_areaCorrect() {
        // Equilateral triangle side=2: area = sqrt(3)/4 * 4 = sqrt(3)
        var tri = ShapeFactory.triangle(2, 2, 2);
        assertThat(tri.area()).isCloseTo(Math.sqrt(3), withinPercentage(0.001));
    }

    @Test
    void triangle_perimeter_isCorrect() {
        var tri = ShapeFactory.triangle(3, 4, 5);
        assertThat(tri.perimeter()).isEqualTo(12.0);
    }

    @Test
    void triangle_invalidSides_throws() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> ShapeFactory.triangle(1, 1, 10)); // violates inequality
        assertThatIllegalArgumentException()
                .isThrownBy(() -> ShapeFactory.triangle(-1, 3, 3));
    }

    // ── describe: exhaustive pattern match ────────────────────────────────────

    @Test
    void describe_circle_containsRadius() {
        var desc = ShapeFactory.describe(ShapeFactory.circle(2.5));
        assertThat(desc).containsIgnoringCase("circle").contains("2.50");
    }

    @Test
    void describe_rectangle_containsDimensions() {
        var desc = ShapeFactory.describe(ShapeFactory.rectangle(3.0, 4.0));
        assertThat(desc).containsIgnoringCase("rectangle").contains("3.00").contains("4.00");
    }

    @Test
    void describe_triangle_containsSides() {
        var desc = ShapeFactory.describe(ShapeFactory.triangle(3, 4, 5));
        assertThat(desc).containsIgnoringCase("triangle");
    }
}
