package org.axl.javac;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.axl.compiler.AxlDiagnostic;
import org.axl.compiler.ErrorCode;
import org.axl.compiler.Options;
import org.axl.compiler.Translator;
import org.axl.compiler.ast.SourceFile;
import org.axl.compiler.parse.SourceParser;

/**
 * Translates a set of .axl files and writes the Java to a directory. The translator reads the
 * declarations of the other files of the build as well: an interface a class implements, a data
 * class a pattern takes apart. Top-level values whose type depends on library code are first written
 * as drafts typed `Object`; javac then reads their types with the drafts of the other files on its
 * source path.
 */
public final class Translation {

    /** An .axl file: its path relative to its source root, how diagnostics name it, and its text. */
    public record Input(String sourcePath, String displayPath, String text) {}

    private static SourceParser.Result parse(Input input) {
        return Translator.parse(input.sourcePath(), input.displayPath(), input.text());
    }

    /** The other files of the build that parsed, so that the translator reads their declarations. */
    private static Options withSiblings(Options options, List<SourceParser.Result> parsed, int own) {
        List<SourceFile> siblings = new ArrayList<>();
        for (int i = 0; i < parsed.size(); i++) {
            if (i != own && parsed.get(i).file() != null) {
                siblings.add(parsed.get(i).file());
            }
        }
        return options.withSiblings(siblings);
    }

    /** Two files that declare the same class would overwrite each other's Java. */
    private static void rejectDuplicateClasses(List<Input> inputs, List<Translator.Result> results, Path outputDir)
            throws IOException {
        Map<String, List<Integer>> byClass = new HashMap<>();
        for (int i = 0; i < results.size(); i++) {
            if (results.get(i).unit() != null) {
                byClass.computeIfAbsent(results.get(i).unit().qualifiedName(), k -> new ArrayList<>()).add(i);
            }
        }
        for (var entry : byClass.entrySet()) {
            List<Integer> same = entry.getValue();
            if (same.size() < 2) {
                continue;
            }
            Translator.Result first = results.get(same.getFirst());
            Files.deleteIfExists(outputDir.resolve(first.unit().javaPath()));
            Files.deleteIfExists(outputDir.resolve(first.unit().mapPath()));
            String files = same.stream().map(i -> inputs.get(i).displayPath()).collect(Collectors.joining(", "));
            for (int i : same) {
                List<AxlDiagnostic> diagnostics = new ArrayList<>(results.get(i).diagnostics());
                diagnostics.add(AxlDiagnostic.error(inputs.get(i).displayPath(), 1, 1, ErrorCode.DUPLICATE_CLASS,
                        "the class `" + entry.getKey() + "` is declared by several files: " + files));
                results.set(i, new Translator.Result(null, diagnostics, false));
            }
        }
    }

    private Translation() {}

    /** Whether every error is about a value typed `Object` or a class whose own types are unknown. */
    private static boolean fromDrafts(Translator.Result result, Set<String> drafts) {
        return result.diagnostics().stream().filter(AxlDiagnostic::isError).allMatch(d -> d.message().contains("`Object`")
                || drafts.stream().anyMatch(name -> d.message().startsWith("unknown name `" + simpleName(name) + "`")));
    }

    private static String simpleName(String qualified) {
        return qualified.substring(qualified.lastIndexOf('.') + 1);
    }

    /**
     * @param classpath  the classpath of the code
     * @param javaRoots  directories with the Java sources of the code
     * @param outputDir  where .java and .axlmap files are written
     */
    public static List<Translator.Result> run(List<Input> inputs, Options options, List<Path> classpath,
                                              List<Path> javaRoots, Path outputDir) throws IOException {
        return run(inputs, List.of(), options, classpath, javaRoots, outputDir, false);
    }

    /** strictNull: top-level values inferred from Java code without nullness annotations are nullable. */
    public static List<Translator.Result> run(List<Input> inputs, Options options, List<Path> classpath,
                                              List<Path> javaRoots, Path outputDir, boolean strictNull) throws IOException {
        return run(inputs, List.of(), options, classpath, javaRoots, outputDir, strictNull);
    }

    /**
     * @param context the other files of the build, read for their declarations but not translated,
     *                such as the unchanged files of an incremental build
     */
    public static List<Translator.Result> run(List<Input> inputs, List<Input> context, Options options, List<Path> classpath,
                                              List<Path> javaRoots, Path outputDir, boolean strictNull) throws IOException {
        if (inputs.isEmpty()) {
            return List.of();
        }
        // The classes of the classpath and of the Java sources are read when a type test or a Java interface asks.
        try (JavacClasses classes = new JavacClasses(classpath, javaRoots)) {
            return translate(inputs, context, options.withClasses(classes), classpath, javaRoots, outputDir, strictNull);
        }
    }

    private static List<Translator.Result> translate(List<Input> inputs, List<Input> context, Options options,
                                                     List<Path> classpath, List<Path> javaRoots, Path outputDir,
                                                     boolean strictNull) throws IOException {
        List<Translator.Result> results = new ArrayList<>();
        // Every file is parsed once, for its own translations and as a sibling of the others.
        List<SourceParser.Result> parsed = new ArrayList<>();
        for (Input input : inputs) {
            parsed.add(parse(input));
        }
        for (Input input : context) {
            parsed.add(parse(input));
        }
        for (int i = 0; i < inputs.size(); i++) {
            Input input = inputs.get(i);
            Translator.Result result = Translator.translate(input.sourcePath(), input.displayPath(), input.text(),
                    parsed.get(i), withSiblings(options, parsed, i).asDraft());
            if (result.unit() != null) {
                result.unit().writeTo(outputDir);
            }
            results.add(result);
        }
        rejectDuplicateClasses(inputs, results, outputDir);
        if (results.stream().noneMatch(Translator.Result::draft)) {
            return results;
        }
        List<Path> sourcepath = new ArrayList<>(List.of(outputDir));
        sourcepath.addAll(javaRoots);
        Options typed = options.withJavaTypes(new JavacTypes(classpath, sourcepath, strictNull));
        // A value may depend on a value of another draft, so repeat while types keep being found.
        boolean changed = true;
        while (changed) {
            changed = false;
            for (int i = 0; i < inputs.size(); i++) {
                Translator.Result previous = results.get(i);
                if (!previous.draft()) {
                    continue;
                }
                Input input = inputs.get(i);
                Translator.Result result = Translator.translate(input.sourcePath(), input.displayPath(), input.text(),
                        parsed.get(i), withSiblings(typed, parsed, i).asDraft());
                if (!result.unit().javaSource().equals(previous.unit().javaSource())) {
                    result.unit().writeTo(outputDir);
                    changed = true;
                }
                results.set(i, result);
            }
        }
        Set<String> stillDrafts = new HashSet<>();
        for (int i = 0; i < inputs.size(); i++) {
            if (results.get(i).draft()) {
                stillDrafts.add(results.get(i).unit().qualifiedName());
            }
        }
        for (int i = 0; i < inputs.size(); i++) {
            Translator.Result draft = results.get(i);
            if (draft.draft()) {
                Input input = inputs.get(i);
                Translator.Result result = Translator.translate(input.sourcePath(), input.displayPath(), input.text(),
                        parsed.get(i), withSiblings(typed, parsed, i));
                if (result.unit() == null && fromDrafts(result, stillDrafts)) {
                    // Values of files that read each other stay typed `Object`. javac's errors about
                    // `Object` would not say that; asking for the types does.
                    result = Translator.translate(input.sourcePath(), input.displayPath(), input.text(),
                            parsed.get(i), withSiblings(options, parsed, i));
                }
                results.set(i, result);
                // The draft must not be compiled as if the translation had succeeded.
                Files.deleteIfExists(outputDir.resolve(draft.unit().javaPath()));
                Files.deleteIfExists(outputDir.resolve(draft.unit().mapPath()));
                if (result.unit() != null) {
                    result.unit().writeTo(outputDir);
                }
            }
        }
        return results;
    }
}
