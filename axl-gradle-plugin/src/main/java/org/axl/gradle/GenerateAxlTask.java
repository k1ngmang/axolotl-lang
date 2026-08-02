package org.axl.gradle;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.inject.Inject;
import org.axl.compiler.AxlDiagnostic;
import org.axl.compiler.ErrorCode;
import org.axl.compiler.LineMap;
import org.axl.compiler.Options;
import org.axl.compiler.Translator;
import org.axl.compiler.ast.SourceFile;
import org.axl.compiler.parse.SourceParser;
import org.axl.javac.Translation;
import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.FileSystemOperations;
import org.gradle.api.file.FileType;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.CacheableTask;
import org.gradle.api.tasks.Classpath;
import org.gradle.api.tasks.IgnoreEmptyDirectories;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.OutputDirectory;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.SkipWhenEmpty;
import org.gradle.api.tasks.TaskAction;
import org.gradle.work.ChangeType;
import org.gradle.work.FileChange;
import org.gradle.work.Incremental;
import org.gradle.work.InputChanges;

/** Translates .axl files to .java files and line maps; only changed files are translated again. */
@CacheableTask
public abstract class GenerateAxlTask extends DefaultTask {

    /** The .axl source trees; paths relative to their roots decide the generated paths. */
    // Also makes the property incremental; generateTestAxl without test sources is NO-SOURCE.
    @SkipWhenEmpty
    @IgnoreEmptyDirectories
    @PathSensitive(PathSensitivity.RELATIVE)
    @InputFiles
    public abstract ConfigurableFileCollection getSources();

    /** The .axl sources of the source sets and projects this one compiles against, read for their declarations. */
    @Incremental
    @InputFiles
    @PathSensitive(PathSensitivity.RELATIVE)
    @IgnoreEmptyDirectories
    public abstract ConfigurableFileCollection getContextSources();

    @Input
    public abstract Property<Boolean> getInlineElvis();

    /** Types top-level values inferred from Java code without nullness annotations as nullable. */
    @Input
    public abstract Property<Boolean> getStrictNull();

    /** Classpath and Java sources, for the types of top-level values that depend on them. */
    @Classpath
    public abstract ConfigurableFileCollection getClasspath();

    @InputFiles
    @PathSensitive(PathSensitivity.RELATIVE)
    @IgnoreEmptyDirectories
    public abstract ConfigurableFileCollection getJavaSourceDirs();

    @OutputDirectory
    public abstract DirectoryProperty getOutputDir();

    @Inject
    protected abstract FileSystemOperations getFileSystem();

    @TaskAction
    public void generate(InputChanges changes) throws IOException {
        Path out = getOutputDir().get().getAsFile().toPath();
        if (!changes.isIncremental()) {
            getFileSystem().delete(spec -> spec.delete(out.toFile()));
            Files.createDirectories(out);
        }
        Map<String, List<Path>> outputsBySource = outputsBySource(out);
        Set<String> changed = new HashSet<>();
        Set<String> changedPackages = new HashSet<>();
        boolean removed = false;
        for (FileChange change : changes.getFileChanges(getSources())) {
            if (change.getFileType() == FileType.DIRECTORY) {
                continue;
            }
            String sourcePath = change.getNormalizedPath().replace('\\', '/');
            List<Path> outputs = outputsBySource.remove(sourcePath);
            deleteOutputs(outputs);
            if (change.getChangeType() == ChangeType.REMOVED) {
                removed = true;
                if (outputs != null) {
                    changedPackages.add(packageOf(out.relativize(outputs.getFirst())));
                }
            } else {
                changed.add(sourcePath);
            }
        }
        boolean contextChanged = false;
        for (FileChange change : changes.getFileChanges(getContextSources())) {
            if (change.getFileType() != FileType.DIRECTORY) {
                changedPackages.add(packageOf(Path.of(change.getNormalizedPath())));
                contextChanged = true;
            }
        }
        List<Translation.Input> sources = readSources(getSources());
        Map<Translation.Input, SourceFile> parsed = parseAll(sources);
        for (Translation.Input source : sources) {
            if (changed.contains(source.sourcePath())) {
                changedPackages.add(packageOf(parsed.get(source)));
            }
        }
        Map<String, String> duplicates = duplicateClasses(sources, parsed);
        List<AxlDiagnostic> diagnostics = new ArrayList<>();
        List<Translation.Input> inputs = new ArrayList<>();
        List<Translation.Input> context = readSources(getContextSources());
        boolean anyChange = removed || contextChanged || !changed.isEmpty();
        for (Translation.Input source : sources) {
            String sourcePath = source.sourcePath();
            if (duplicates.containsKey(sourcePath)) {
                deleteOutputs(outputsBySource.remove(sourcePath));
                diagnostics.add(AxlDiagnostic.error(source.displayPath(), 1, 1, ErrorCode.DUPLICATE_CLASS,
                        duplicates.get(sourcePath)));
            } else if (needsTranslation(sourcePath, parsed.get(source), changed, changedPackages, outputsBySource, anyChange)) {
                deleteOutputs(outputsBySource.remove(sourcePath));
                inputs.add(source);
            } else {
                context.add(source);
            }
        }
        translate(inputs, context, out, diagnostics);
    }

    /**
     * Besides changed files: files with inferred top-level types, which may depend on any change, the
     * other files of the package of a changed or removed file, whose checks read its declarations, and
     * files without outputs, whose translation failed before or whose outputs a removed file that
     * declared the same class overwrote.
     */
    private static boolean needsTranslation(String sourcePath, SourceFile file, Set<String> changed, Set<String> changedPackages,
                                            Map<String, List<Path>> outputsBySource, boolean anyChange) {
        return changed.contains(sourcePath) || !outputsBySource.containsKey(sourcePath)
                || anyChange && Translator.infersValueTypes(file) || changedPackages.contains(packageOf(file));
    }

    private static Map<Translation.Input, SourceFile> parseAll(List<Translation.Input> sources) {
        Map<Translation.Input, SourceFile> result = new HashMap<>();
        for (Translation.Input source : sources) {
            result.put(source, SourceParser.parse(source.sourcePath(), Translator.className(source.sourcePath()),
                    source.text()).file());
        }
        return result;
    }

    private static String packageOf(SourceFile file) {
        return file == null || file.packageName() == null ? "" : file.packageName();
    }

    /** The package a relative path tells: of a removed file, from the Java it generated, or of a file of another source set. */
    private static String packageOf(Path relativePath) {
        Path directory = relativePath.getParent();
        return directory == null ? "" : directory.toString().replace('\\', '/').replace('/', '.');
    }

    /** The unchanged files are read for their declarations but not written. */
    private void translate(List<Translation.Input> inputs, List<Translation.Input> context, Path out,
                           List<AxlDiagnostic> diagnostics) throws IOException {
        Options options = new Options(getInlineElvis().get());
        List<Path> classpath = getClasspath().getFiles().stream().map(File::toPath).toList();
        List<Path> javaRoots = getJavaSourceDirs().getFiles().stream().map(File::toPath).toList();
        for (Translator.Result result : Translation.run(inputs, context, options, classpath, javaRoots, out,
                getStrictNull().get())) {
            diagnostics.addAll(result.diagnostics());
        }
        getLogger().info("Translated {} .axl file(s)", inputs.size());
        for (AxlDiagnostic d : diagnostics) {
            if (d.isError()) {
                getLogger().error(d.format());
            } else {
                getLogger().warn(d.format());
            }
        }
        if (diagnostics.stream().anyMatch(AxlDiagnostic::isError)) {
            throw new GradleException("Axolotl translation failed; see the errors above.");
        }
    }

    /** Every .axl file, sorted by its path relative to its source root; two roots may hold the same path. */
    private static List<Translation.Input> readSources(ConfigurableFileCollection files) {
        List<Translation.Input> result = new ArrayList<>();
        files.getAsFileTree().visit(details -> {
            if (details.isDirectory()) {
                return;
            }
            String sourcePath = details.getRelativePath().getPathString();
            try {
                String text = Files.readString(details.getFile().toPath(), StandardCharsets.UTF_8);
                result.add(new Translation.Input(sourcePath, details.getFile().getAbsolutePath(), text));
            } catch (CharacterCodingException e) {
                throw new GradleException(details.getFile().getAbsolutePath() + " is not UTF-8 text", e);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
        result.sort(Comparator.comparing(Translation.Input::sourcePath));
        return result;
    }

    /**
     * Files that declare the same class, with the error for each. Two such files overwrite each
     * other's Java, and only all files together show it, not just the changed ones.
     */
    private static Map<String, String> duplicateClasses(List<Translation.Input> sources, Map<Translation.Input, SourceFile> parsed) {
        Map<String, List<Translation.Input>> byClass = new TreeMap<>();
        for (Translation.Input source : sources) {
            SourceFile file = parsed.get(source);
            if (file != null) {
                String name = packageOf(file).isEmpty() ? file.className() : packageOf(file) + "." + file.className();
                byClass.computeIfAbsent(name, k -> new ArrayList<>()).add(source);
            }
        }
        Map<String, String> result = new TreeMap<>();
        byClass.forEach((name, same) -> {
            if (same.size() > 1) {
                String files = same.stream().map(Translation.Input::displayPath).collect(Collectors.joining(", "));
                same.forEach(s -> result.put(s.sourcePath(), "the class `" + name + "` is declared by several files: " + files));
            }
        });
        return result;
    }

    private static void deleteOutputs(List<Path> outputs) throws IOException {
        if (outputs == null) {
            return;
        }
        for (Path output : outputs) {
            Files.deleteIfExists(output);
        }
    }

    /** Generated files keyed by the .axl path recorded in their line maps. */
    private static Map<String, List<Path>> outputsBySource(Path out) throws IOException {
        Map<String, List<Path>> result = new HashMap<>();
        if (!Files.isDirectory(out)) {
            return result;
        }
        try (Stream<Path> files = Files.walk(out)) {
            for (Path map : files.filter(p -> p.toString().endsWith(".axlmap")).toList()) {
                String source = LineMap.fromJson(Files.readString(map, StandardCharsets.UTF_8)).source();
                String name = map.getFileName().toString();
                Path java = map.resolveSibling(name.substring(0, name.length() - ".axlmap".length()) + ".java");
                result.computeIfAbsent(source, k -> new ArrayList<>()).addAll(List.of(map, java));
            }
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }
        return result;
    }
}
