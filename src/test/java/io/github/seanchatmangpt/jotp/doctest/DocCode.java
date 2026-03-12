package io.github.seanchatmangpt.jotp.doctest;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Attaches a highlighted code snippet to a test method for inclusion in the generated HTML
 * documentation.
 *
 * <p>Inspired by the {@code @DocCode} annotation in
 * <a href="https://github.com/seanchatmangpt/doctester">seanchatmangpt/doctester</a>.
 *
 * <p>The snippet is rendered inside a {@code <pre><code>} block in the output document. If
 * {@link #value()} is empty, the test method's own source is used as the code example.
 *
 * <pre>{@code
 * @DocCode("""
 *     Proc<Integer, String> p = new Proc<>(0, (s, m) -> s + 1);
 *     p.tell("ping");
 *     """)
 * @Test
 * void procTell() { ... }
 * }</pre>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface DocCode {
    /**
     * Java source snippet to display. Leave empty to have the extension record the test body.
     *
     * @return the code snippet
     */
    String value() default "";
}
