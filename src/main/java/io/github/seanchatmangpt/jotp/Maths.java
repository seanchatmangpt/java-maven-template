package io.github.seanchatmangpt.jotp;

/**
 * Simple arithmetic operations — demonstration class for testing patterns.
 *
 * <p>This class demonstrates the Java 26 record patterns and serves as a
 * simple example for testing the build system and test infrastructure.
 *
 * <p><strong>Usage:</strong>
 *
 * <pre>{@code
 * var maths = new Maths();
 * var result = maths.sum(new Maths.Input(5, 3));
 * // result.result() == 8
 * }</pre>
 *
 * @see Input
 * @see Output
 */
public class Maths {

    /**
     * Input record for arithmetic operations.
     *
     * @param x first operand
     * @param y second operand
     */
    public record Input(int x, int y) {}

    /**
     * Output record for arithmetic operations.
     *
     * @param result the computed result
     */
    public record Output(long result) {}

    /**
     * Compute the sum of two integers.
     *
     * @param input the input operands
     * @return the sum as an Output record
     */
    public Output sum(final Input input) {
        return new Output(input.x() + input.y());
    }
}
