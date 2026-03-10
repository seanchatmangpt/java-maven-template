package org.acme.doctest;

import java.io.IOException;
import java.io.PrintWriter;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.TestWatcher;

/**
 * JUnit 5 extension that generates Bootstrap-styled HTML documentation from annotated tests.
 *
 * <p>Inspired by <a href="https://github.com/seanchatmangpt/doctester">seanchatmangpt/doctester</a>
 * and the original <a href="https://doctester.org">doctester</a> project: tests and documentation
 * are generated simultaneously so they can never drift apart.
 *
 * <p><b>Usage:</b>
 *
 * <pre>{@code
 * @ExtendWith(DocTestExtension.class)
 * class ProcDocIT {
 *
 *     @DocSection("Process Creation")
 *     @DocNote("A Proc is created with an initial state and a pure handler.")
 *     @DocCode("new Proc<>(0, (s, msg) -> s + 1)")
 *     @Test
 *     void createProc() {
 *         var p = new Proc<>(0, (s, m) -> s + 1);
 *         assertThat(p.isRunning()).isTrue();
 *         p.stop();
 *     }
 * }
 * }</pre>
 *
 * <p>Output is written to {@code target/site/doctester/<ClassName>.html} and an index is
 * maintained at {@code target/site/doctester/index.html}.
 */
public final class DocTestExtension
        implements BeforeAllCallback, AfterAllCallback, TestWatcher {

    // ── Per-class state stored via the store ────────────────────────────────────

    private static final ExtensionContext.Namespace NS =
            ExtensionContext.Namespace.create(DocTestExtension.class);

    private static final String KEY = "entries";

    // Shared registry of all generated docs (for index generation)
    private static final Map<String, String> generatedDocs = new ConcurrentHashMap<>();

    // ── BeforeAllCallback ────────────────────────────────────────────────────────

    @Override
    public void beforeAll(ExtensionContext ctx) {
        ctx.getStore(NS).put(KEY, new ArrayList<DocEntry>());
    }

    // ── TestWatcher ──────────────────────────────────────────────────────────────

    @Override
    public void testSuccessful(ExtensionContext ctx) {
        record(ctx, "PASSED", null);
    }

    @Override
    public void testFailed(ExtensionContext ctx, Throwable cause) {
        record(ctx, "FAILED", cause);
    }

    @Override
    public void testAborted(ExtensionContext ctx, Throwable cause) {
        record(ctx, "ABORTED", cause);
    }

    @Override
    public void testDisabled(ExtensionContext ctx, Optional<String> reason) {
        record(ctx, "DISABLED", null);
    }

    // ── AfterAllCallback ─────────────────────────────────────────────────────────

    @Override
    public void afterAll(ExtensionContext ctx) throws Exception {
        @SuppressWarnings("unchecked")
        List<DocEntry> entries =
                (List<DocEntry>) ctx.getStore(NS).get(KEY, List.class);

        if (entries == null || entries.isEmpty()) return;

        Class<?> testClass = ctx.getRequiredTestClass();
        String className = testClass.getSimpleName();
        String docTitle = className.replace("DocIT", "").replace("IT", "").replace("Test", "");

        Path outDir = Path.of("target", "site", "doctester");
        Files.createDirectories(outDir);

        Path htmlFile = outDir.resolve(className + ".html");
        writeHtml(htmlFile, docTitle, entries);
        generatedDocs.put(className, htmlFile.getFileName().toString());

        writeIndex(outDir);
    }

    // ── Internal record helper ───────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private void record(ExtensionContext ctx, String status, Throwable cause) {
        List<DocEntry> entries = (List<DocEntry>) ctx.getStore(NS).get(KEY, List.class);
        if (entries == null) return;

        Method method = ctx.getTestMethod().orElse(null);
        if (method == null) return;

        var entry = new DocEntry(
                ctx.getDisplayName(),
                status,
                method.getAnnotation(DocSection.class),
                method.getAnnotation(DocNote.class),
                method.getAnnotation(DocCode.class),
                method.getAnnotation(DocWarning.class),
                cause);
        entries.add(entry);
    }

    // ── HTML generation ──────────────────────────────────────────────────────────

    private void writeHtml(Path file, String title, List<DocEntry> entries) throws IOException {
        try (PrintWriter w =
                new PrintWriter(
                        Files.newBufferedWriter(
                                file,
                                StandardCharsets.UTF_8,
                                StandardOpenOption.CREATE,
                                StandardOpenOption.TRUNCATE_EXISTING))) {

            w.println("<!DOCTYPE html>");
            w.println("<html lang=\"en\">");
            w.println("<head>");
            w.println(
                    "  <meta charset=\"UTF-8\"><meta name=\"viewport\""
                            + " content=\"width=device-width, initial-scale=1\">");
            w.println(
                    "  <link rel=\"stylesheet\""
                        + " href=\"https://cdn.jsdelivr.net/npm/bootstrap@5.3.0/dist/css/bootstrap.min.css\">");
            w.printf("  <title>%s — jOTP DocTest</title>%n", escape(title));
            w.println("  <style>");
            w.println("    body { padding: 2rem; }");
            w.println("    .test-passed { border-left: 4px solid #198754; }");
            w.println("    .test-failed { border-left: 4px solid #dc3545; }");
            w.println("    .test-disabled { border-left: 4px solid #adb5bd; }");
            w.println("    pre { background: #f8f9fa; padding: 1rem; border-radius: 4px; }");
            w.println("  </style>");
            w.println("</head>");
            w.println("<body>");
            w.printf(
                    "<div class=\"container\">%n<h1>%s</h1>%n"
                            + "<p class=\"text-muted\">Generated %s · jOTP DocTest</p>%n",
                    escape(title), Instant.now());
            w.println("<a href=\"index.html\" class=\"btn btn-sm btn-outline-secondary mb-3\">"
                    + "← All docs</a>");

            // Group by section
            Map<String, List<DocEntry>> sections = new LinkedHashMap<>();
            for (DocEntry e : entries) {
                String section = e.section() != null ? e.section().value() : "Tests";
                sections.computeIfAbsent(section, k -> new ArrayList<>()).add(e);
            }

            for (Map.Entry<String, List<DocEntry>> sec : sections.entrySet()) {
                w.printf("<h2 class=\"mt-4\">%s</h2>%n", escape(sec.getKey()));
                for (DocEntry e : sec.getValue()) {
                    String cssClass = switch (e.status()) {
                        case "PASSED" -> "test-passed";
                        case "FAILED" -> "test-failed";
                        default -> "test-disabled";
                    };
                    w.printf("<div class=\"card mb-3 %s\">%n<div class=\"card-body\">%n",
                            cssClass);
                    w.printf(
                            "<h5 class=\"card-title\">%s "
                                    + "<span class=\"badge bg-%s\">%s</span></h5>%n",
                            escape(e.displayName()),
                            badgeBg(e.status()),
                            e.status());

                    if (e.note() != null) {
                        w.printf("<p>%s</p>%n", escape(e.note().value()));
                    }
                    if (e.warning() != null) {
                        w.printf(
                                "<div class=\"alert alert-warning\">⚠ %s</div>%n",
                                escape(e.warning().value()));
                    }
                    if (e.code() != null && !e.code().value().isBlank()) {
                        w.printf("<pre><code>%s</code></pre>%n", escape(e.code().value()));
                    }
                    if (e.cause() != null && "FAILED".equals(e.status())) {
                        w.printf(
                                "<div class=\"alert alert-danger\">%s</div>%n",
                                escape(e.cause().getMessage()));
                    }
                    w.println("</div></div>");
                }
            }

            w.println("</div></body></html>");
        }
    }

    private void writeIndex(Path outDir) throws IOException {
        Path index = outDir.resolve("index.html");
        try (PrintWriter w =
                new PrintWriter(
                        Files.newBufferedWriter(
                                index,
                                StandardCharsets.UTF_8,
                                StandardOpenOption.CREATE,
                                StandardOpenOption.TRUNCATE_EXISTING))) {

            w.println("<!DOCTYPE html><html lang=\"en\"><head>");
            w.println(
                    "<meta charset=\"UTF-8\">"
                        + "<link rel=\"stylesheet\""
                        + " href=\"https://cdn.jsdelivr.net/npm/bootstrap@5.3.0/dist/css/bootstrap.min.css\">");
            w.println("<title>jOTP DocTest Index</title></head>");
            w.println("<body><div class=\"container\" style=\"padding:2rem\">");
            w.println("<h1>jOTP DocTest</h1>");
            w.printf("<p class=\"text-muted\">Last generated: %s</p>%n", Instant.now());
            w.println("<ul class=\"list-group\">");
            for (Map.Entry<String, String> doc : generatedDocs.entrySet()) {
                w.printf(
                        "<li class=\"list-group-item\">"
                                + "<a href=\"%s\">%s</a></li>%n",
                        escape(doc.getValue()), escape(doc.getKey()));
            }
            w.println("</ul></div></body></html>");
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────────

    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    private static String badgeBg(String status) {
        return switch (status) {
            case "PASSED" -> "success";
            case "FAILED" -> "danger";
            default -> "secondary";
        };
    }

    // ── DocEntry record ──────────────────────────────────────────────────────────

    private record DocEntry(
            String displayName,
            String status,
            DocSection section,
            DocNote note,
            DocCode code,
            DocWarning warning,
            Throwable cause) {}
}
