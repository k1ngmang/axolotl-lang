package org.axl.javac;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.axl.compiler.Options;
import org.axl.compiler.Translator;

/**
 * Translates a set of .axl files and writes the Java to a directory. Top-level values whose type
 * depends on library code are first written as drafts typed `Object`; javac then reads their types
 * with the drafts of the other files on its source path.
 */
public final class Translation {

    /** An .axl file: its path relative to its source root, how diagnostics name it, and its text. */
    public record Input(String sourcePath, String displayPath, String text) {}

    private Translation() {}

    /**
     * @param classpath  the classpath of the code
     * @param javaRoots  directories with the Java sources of the code
     * @param outputDir  where .java and .axlmap files are written
     */
    public static List<Translator.Result> run(List<Input> inputs, Options options, List<Path> classpath,
                                              List<Path> javaRoots, Path outputDir) throws IOException {
        return run(inputs, options, classpath, javaRoots, outputDir, false);
    }

    /** strictNull: top-level values inferred from Java code without nullness annotations are nullable. */
    public static List<Translator.Result> run(List<Input> inputs, Options options, List<Path> classpath,
                                              List<Path> javaRoots, Path outputDir, boolean strictNull) throws IOException {
        List<Translator.Result> results = new ArrayList<>();
        for (Input input : inputs) {
            Translator.Result result = Translator.translate(input.sourcePath(), input.displayPath(), input.text(), options.asDraft());
            if (result.unit() != null) {
                result.unit().writeTo(outputDir);
            }
            results.add(result);
        }
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
                        typed.asDraft());
                if (!result.unit().javaSource().equals(previous.unit().javaSource())) {
                    result.unit().writeTo(outputDir);
                    changed = true;
                }
                results.set(i, result);
            }
        }
        for (int i = 0; i < inputs.size(); i++) {
            Translator.Result draft = results.get(i);
            if (draft.draft()) {
                Input input = inputs.get(i);
                results.set(i, Translator.translate(input.sourcePath(), input.displayPath(), input.text(), typed));
                // The draft must not be compiled as if the translation had succeeded.
                Files.deleteIfExists(outputDir.resolve(draft.unit().javaPath()));
                Files.deleteIfExists(outputDir.resolve(draft.unit().mapPath()));
            }
        }
        return results;
    }
}
