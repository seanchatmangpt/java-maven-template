package io.github.seanchatmangpt.jotp.doctest;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Attaches a warning callout to a test method for inclusion in the generated HTML documentation.
 *
 * <p>Inspired by the {@code @DocWarning} annotation in
 * <a href="https://github.com/seanchatmangpt/doctester">seanchatmangpt/doctester</a>.
 *
 * <p>Rendered as a Bootstrap {@code alert-warning} panel in the output document.
 *
 * <pre>{@code
 * @DocWarning("Never share Proc state across thread boundaries — the handler must be pure.")
 * @Test
 * void procIsolation() { ... }
 * }</pre>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface DocWarning {
    /**
     * Warning message to highlight.
     *
     * @return the warning text
     */
    String value();
}
