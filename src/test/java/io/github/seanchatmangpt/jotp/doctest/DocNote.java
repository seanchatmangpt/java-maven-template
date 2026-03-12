package io.github.seanchatmangpt.jotp.doctest;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Attaches a prose note to a test method for inclusion in the generated HTML documentation.
 *
 * <p>Inspired by the {@code say(text)} API in
 * <a href="https://github.com/seanchatmangpt/doctester">seanchatmangpt/doctester</a>.
 *
 * <p>The note text is rendered as a {@code <p>} paragraph in the output document, appearing
 * directly above the test result block.
 *
 * <pre>{@code
 * @DocNote("A Proc is created with an initial state and a pure (state, msg) -> nextState handler.")
 * @Test
 * void procCreation() { ... }
 * }</pre>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface DocNote {
    /**
     * The note text to render as a paragraph.
     *
     * @return the paragraph text
     */
    String value();
}
