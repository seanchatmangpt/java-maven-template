package org.acme.dogfood.innovation;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Living Documentation Generator — produces Markdown documentation directly from Java source code.
 *
 * <p>Instead of static docs that rot, this generator extracts structural information (records, sealed
 * types, module declarations, method signatures) from Java source strings via regex-based parsing and
 * renders always-in-sync Markdown documentation.
 *
 * <p>Demonstrates modern Java: sealed interfaces, records, pattern matching, text blocks, and streams.
 */
public final class LivingDocGenerator {

    // -- Sealed DocElement hierarchy ----------------------------------------------------------

    /** A documentation element extracted from Java source code. */
    public sealed interface DocElement
            permits DocElement.RecordDoc,
                    DocElement.SealedTypeDoc,
                    DocElement.ModuleDoc,
                    DocElement.MethodDoc,
                    DocElement.PackageDoc {

        /** Documentation for a Java record. */
        record RecordDoc(String name, List<Component> components, String javadoc)
                implements DocElement {
            public RecordDoc {
                Objects.requireNonNull(name, "name must not be null");
                components = List.copyOf(components);
            }
        }

        /** A single record component (name + type). */
        record Component(String type, String name) {
            public Component {
                Objects.requireNonNull(type, "type must not be null");
                Objects.requireNonNull(name, "name must not be null");
            }
        }

        /** Documentation for a sealed interface or class. */
        record SealedTypeDoc(String name, String kind, List<String> permits, String javadoc)
                implements DocElement {
            public SealedTypeDoc {
                Objects.requireNonNull(name, "name must not be null");
                Objects.requireNonNull(kind, "kind must not be null");
                permits = List.copyOf(permits);
            }
        }

        /** Documentation for a JPMS module declaration. */
        record ModuleDoc(String name, List<String> exports, List<String> requires)
                implements DocElement {
            public ModuleDoc {
                Objects.requireNonNull(name, "name must not be null");
                exports = List.copyOf(exports);
                requires = List.copyOf(requires);
            }
        }

        /** Documentation for a method signature. */
        record MethodDoc(
                String name,
                String returnType,
                List<Component> parameters,
                List<String> modifiers,
                String javadoc)
                implements DocElement {
            public MethodDoc {
                Objects.requireNonNull(name, "name must not be null");
                Objects.requireNonNull(returnType, "returnType must not be null");
                parameters = List.copyOf(parameters);
                modifiers = List.copyOf(modifiers);
            }
        }

        /** Documentation for a package declaration. */
        record PackageDoc(String name, String javadoc) implements DocElement {
            public PackageDoc {
                Objects.requireNonNull(name, "name must not be null");
            }
        }
    }

    // -- Regex patterns -----------------------------------------------------------------------

    private static final Pattern PACKAGE_PATTERN =
            Pattern.compile("(?:^|\\n)\\s*package\\s+([\\w.]+)\\s*;");

    private static final Pattern RECORD_PATTERN =
            Pattern.compile(
                    "(?:/\\*\\*([^*]|\\*(?!/))*\\*/\\s*)?"
                            + "(?:public\\s+)?record\\s+(\\w+)"
                            + "(?:<[^>]*>)?"
                            + "\\s*\\(([^)]*)\\)");

    private static final Pattern SEALED_PATTERN =
            Pattern.compile(
                    "(?:/\\*\\*([^*]|\\*(?!/))*\\*/\\s*)?"
                            + "(?:public\\s+)?sealed\\s+(interface|class)\\s+(\\w+)"
                            + "(?:<[^>]*>)?\\s+"
                            + "permits\\s+([^{]+)\\{");

    private static final Pattern METHOD_PATTERN =
            Pattern.compile(
                    "(?:/\\*\\*([^*]|\\*(?!/))*\\*/\\s*)?"
                            + "((?:(?:public|protected|private|static|default|final|abstract|synchronized)\\s+)+)"
                            + "(?:<[^>]*>\\h+)?"
                            + "([\\w<>\\[\\]?,\\h]+?)\\h+"
                            + "(\\w+)\\s*\\(([^)]*)\\)");

    private static final Pattern MODULE_PATTERN =
            Pattern.compile("(?:open\\s+)?module\\s+([\\w.]+)\\s*\\{([^}]*)}");

    private static final Pattern EXPORTS_PATTERN =
            Pattern.compile("exports\\s+([\\w.]+)\\s*;");

    private static final Pattern REQUIRES_PATTERN =
            Pattern.compile("requires\\s+(?:transitive\\s+)?([\\w.]+)\\s*;");

    private static final Pattern COMPONENT_PATTERN =
            Pattern.compile("\\s*([\\w<>\\[\\]?,\\s]+?)\\s+(\\w+)\\s*");

    private static final Pattern JAVADOC_EXTRACT =
            Pattern.compile("/\\*\\*\\s*(.*?)\\s*\\*/", Pattern.DOTALL);

    // -- Public API ---------------------------------------------------------------------------

    /**
     * Parses Java source code and extracts documentation elements.
     *
     * @param javaSource the raw Java source text
     * @return a list of extracted {@link DocElement}s
     */
    public List<DocElement> parseSource(String javaSource) {
        Objects.requireNonNull(javaSource, "javaSource must not be null");
        var elements = new ArrayList<DocElement>();

        parsePackages(javaSource, elements);
        parseModules(javaSource, elements);
        parseSealedTypes(javaSource, elements);
        parseRecords(javaSource, elements);
        parseMethods(javaSource, elements);

        return List.copyOf(elements);
    }

    /**
     * Generates a Markdown document from a list of documentation elements.
     *
     * @param elements the extracted documentation elements
     * @return a Markdown-formatted string
     */
    public String generateMarkdown(List<DocElement> elements) {
        Objects.requireNonNull(elements, "elements must not be null");
        if (elements.isEmpty()) {
            return "# Documentation\n\n_No elements found._\n";
        }

        var sb = new StringBuilder();
        sb.append("# API Documentation\n\n");

        // Group and render by type
        renderSection(sb, "Packages", filterByType(elements, DocElement.PackageDoc.class));
        renderSection(sb, "Modules", filterByType(elements, DocElement.ModuleDoc.class));
        renderSection(
                sb, "Sealed Types", filterByType(elements, DocElement.SealedTypeDoc.class));
        renderSection(sb, "Records", filterByType(elements, DocElement.RecordDoc.class));
        renderSection(sb, "Methods", filterByType(elements, DocElement.MethodDoc.class));

        return sb.toString();
    }

    // -- Parsing helpers ----------------------------------------------------------------------

    private void parsePackages(String source, List<DocElement> elements) {
        Matcher m = PACKAGE_PATTERN.matcher(source);
        while (m.find()) {
            String pkgName = m.group(1);
            String javadoc = extractPrecedingJavadoc(source, m.start());
            elements.add(new DocElement.PackageDoc(pkgName, javadoc));
        }
    }

    private void parseModules(String source, List<DocElement> elements) {
        Matcher m = MODULE_PATTERN.matcher(source);
        while (m.find()) {
            String moduleName = m.group(1);
            String body = m.group(2);

            List<String> exports = new ArrayList<>();
            Matcher em = EXPORTS_PATTERN.matcher(body);
            while (em.find()) {
                exports.add(em.group(1));
            }

            List<String> requires = new ArrayList<>();
            Matcher rm = REQUIRES_PATTERN.matcher(body);
            while (rm.find()) {
                requires.add(rm.group(1));
            }

            elements.add(new DocElement.ModuleDoc(moduleName, exports, requires));
        }
    }

    private void parseSealedTypes(String source, List<DocElement> elements) {
        Matcher m = SEALED_PATTERN.matcher(source);
        while (m.find()) {
            String javadoc = extractJavadocFromFullMatch(m.group(0));
            String kind = m.group(2);
            String name = m.group(3);
            String permitsStr = m.group(4).trim();
            List<String> permits =
                    List.of(permitsStr.split("\\s*,\\s*")).stream()
                            .map(String::trim)
                            .filter(s -> !s.isEmpty())
                            .toList();
            elements.add(new DocElement.SealedTypeDoc(name, kind, permits, javadoc));
        }
    }

    private void parseRecords(String source, List<DocElement> elements) {
        Matcher m = RECORD_PATTERN.matcher(source);
        while (m.find()) {
            String javadoc = extractJavadocFromFullMatch(m.group(0));
            String name = m.group(2);
            String componentsStr = m.group(3).trim();
            List<DocElement.Component> components = parseComponents(componentsStr);
            elements.add(new DocElement.RecordDoc(name, components, javadoc));
        }
    }

    private void parseMethods(String source, List<DocElement> elements) {
        Matcher m = METHOD_PATTERN.matcher(source);
        while (m.find()) {
            String javadoc = extractJavadocFromFullMatch(m.group(0));
            String modifiersStr = m.group(2).trim();
            String returnType = m.group(3).trim();
            String name = m.group(4);
            String paramsStr = m.group(5).trim();

            // Skip constructors (return type == name), record/class/interface declarations
            if (returnType.equals(name)
                    || returnType.contains("class ")
                    || returnType.contains("interface ")
                    || returnType.contains("record ")
                    || returnType.contains("enum ")
                    || returnType.equals("new")
                    || name.equals("if")
                    || name.equals("for")
                    || name.equals("while")
                    || name.equals("switch")
                    || name.equals("catch")
                    || name.equals("return")) {
                continue;
            }

            List<String> modifiers =
                    modifiersStr.isEmpty()
                            ? List.of()
                            : List.of(modifiersStr.split("\\s+")).stream()
                                    .filter(s -> !s.isEmpty())
                                    .toList();

            List<DocElement.Component> params =
                    paramsStr.isEmpty() ? List.of() : parseComponents(paramsStr);

            elements.add(new DocElement.MethodDoc(name, returnType, params, modifiers, javadoc));
        }
    }

    private List<DocElement.Component> parseComponents(String componentsStr) {
        if (componentsStr == null || componentsStr.isBlank()) {
            return List.of();
        }

        List<DocElement.Component> components = new ArrayList<>();
        // Split on commas not inside angle brackets
        List<String> parts = splitOnTopLevelCommas(componentsStr);

        for (String part : parts) {
            String trimmed = part.trim();
            if (trimmed.isEmpty()) continue;

            // Find the last whitespace to split type from name
            int lastSpace = trimmed.lastIndexOf(' ');
            if (lastSpace > 0) {
                String type = trimmed.substring(0, lastSpace).trim();
                String name = trimmed.substring(lastSpace + 1).trim();
                components.add(new DocElement.Component(type, name));
            }
        }

        return components;
    }

    private List<String> splitOnTopLevelCommas(String str) {
        if (str == null || str.isBlank()) {
            return List.of();
        }

        List<String> parts = new ArrayList<>();
        int depth = 0;
        int start = 0;
        for (int i = 0; i < str.length(); i++) {
            char c = str.charAt(i);
            if (c == '<') depth++;
            else if (c == '>') {
                depth--;
                // Detect unbalanced brackets - clamp to 0 to prevent negative depth
                if (depth < 0) {
                    depth = 0;
                }
            }
            else if (c == ',' && depth == 0) {
                parts.add(str.substring(start, i));
                start = i + 1;
            }
        }
        parts.add(str.substring(start));
        return parts;
    }

    private String extractPrecedingJavadoc(String source, int position) {
        // Look back from position for a javadoc comment
        String before = source.substring(0, position).stripTrailing();
        if (before.endsWith("*/")) {
            int start = before.lastIndexOf("/**");
            if (start >= 0) {
                return cleanJavadoc(before.substring(start));
            }
        }
        return null;
    }

    private String extractJavadocFromFullMatch(String match) {
        Matcher jm = JAVADOC_EXTRACT.matcher(match);
        if (jm.find()) {
            return cleanJavadoc(jm.group(0));
        }
        return null;
    }

    private String cleanJavadoc(String raw) {
        if (raw == null) return null;
        return raw.replaceAll("/\\*\\*\\s*", "")
                .replaceAll("\\s*\\*/", "")
                .replaceAll("(?m)^\\s*\\*\\s?", "")
                .trim();
    }

    // -- Markdown rendering -------------------------------------------------------------------

    private <T extends DocElement> List<T> filterByType(List<DocElement> elements, Class<T> type) {
        return elements.stream().filter(type::isInstance).map(type::cast).toList();
    }

    @SuppressWarnings("unchecked")
    private void renderSection(StringBuilder sb, String title, List<? extends DocElement> items) {
        if (items.isEmpty()) return;

        sb.append("## ").append(title).append("\n\n");

        for (DocElement item : items) {
            switch (item) {
                case DocElement.PackageDoc pkg -> renderPackage(sb, pkg);
                case DocElement.ModuleDoc mod -> renderModule(sb, mod);
                case DocElement.SealedTypeDoc sealed -> renderSealedType(sb, sealed);
                case DocElement.RecordDoc rec -> renderRecord(sb, rec);
                case DocElement.MethodDoc method -> renderMethod(sb, method);
            }
        }
    }

    private void renderPackage(StringBuilder sb, DocElement.PackageDoc pkg) {
        sb.append("### `").append(pkg.name()).append("`\n\n");
        if (pkg.javadoc() != null) {
            sb.append(pkg.javadoc()).append("\n\n");
        }
    }

    private void renderModule(StringBuilder sb, DocElement.ModuleDoc mod) {
        sb.append("### Module `").append(mod.name()).append("`\n\n");
        if (!mod.exports().isEmpty()) {
            sb.append("**Exports:**\n");
            mod.exports().forEach(e -> sb.append("- `").append(e).append("`\n"));
            sb.append("\n");
        }
        if (!mod.requires().isEmpty()) {
            sb.append("**Requires:**\n");
            mod.requires().forEach(r -> sb.append("- `").append(r).append("`\n"));
            sb.append("\n");
        }
    }

    private void renderSealedType(StringBuilder sb, DocElement.SealedTypeDoc sealedType) {
        sb.append("### `sealed ")
                .append(sealedType.kind())
                .append(" ")
                .append(sealedType.name())
                .append("`\n\n");
        if (sealedType.javadoc() != null) {
            sb.append(sealedType.javadoc()).append("\n\n");
        }
        if (!sealedType.permits().isEmpty()) {
            sb.append("**Permitted subtypes:**\n");
            sealedType.permits().forEach(p -> sb.append("- `").append(p).append("`\n"));
            sb.append("\n");
        }
    }

    private void renderRecord(StringBuilder sb, DocElement.RecordDoc rec) {
        sb.append("### `record ").append(rec.name()).append("`\n\n");
        if (rec.javadoc() != null) {
            sb.append(rec.javadoc()).append("\n\n");
        }
        if (!rec.components().isEmpty()) {
            sb.append("| Component | Type |\n");
            sb.append("|-----------|------|\n");
            rec.components()
                    .forEach(
                            c ->
                                    sb.append("| `")
                                            .append(c.name())
                                            .append("` | `")
                                            .append(c.type())
                                            .append("` |\n"));
            sb.append("\n");
        }
    }

    private void renderMethod(StringBuilder sb, DocElement.MethodDoc method) {
        String signature =
                method.parameters().stream()
                        .map(p -> p.type() + " " + p.name())
                        .collect(Collectors.joining(", "));
        String modStr =
                method.modifiers().isEmpty()
                        ? ""
                        : String.join(" ", method.modifiers()) + " ";

        sb.append("#### `")
                .append(modStr)
                .append(method.returnType())
                .append(" ")
                .append(method.name())
                .append("(")
                .append(signature)
                .append(")`\n\n");

        if (method.javadoc() != null) {
            sb.append(method.javadoc()).append("\n\n");
        }
    }
}
