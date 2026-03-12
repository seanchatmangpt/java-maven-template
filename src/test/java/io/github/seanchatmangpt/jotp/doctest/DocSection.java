package io.github.seanchatmangpt.jotp.doctest;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks the start of a documentation section in a {@link DocTestExtension}-driven test class.
 *
 * <p>Inspired by the {@code sayNextSection()} API in
 * <a href="https://github.com/seanchatmangpt/doctester">seanchatmangpt/doctester</a>.
 *
 * <p>Place on a test method or on the test class itself. The generated HTML renders this as an
 * {@code <h2>} headline in the output document.
 *
 * <pre>{@code
 * @DocSection("Process Lifecycle")
 * @Test
 * void createAndStop() { ... }
 * }</pre>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.TYPE})
public @interface DocSection {
    /**
     * Section headline text.
     *
     * @return the headline
     */
    String value();
}
