package org.axl.gradle;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.inject.Inject;
import org.axl.compiler.AxlDiagnostic;
import org.axl.compiler.LineMap;
import org.axl.compiler.Options;
import org.axl.compiler.Translator;
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
        Options options = new Options(getInlineElvis().get());
        List<Translation.Input> inputs = new ArrayList<>();
        for (FileChange change : changes.getFileChanges(getSources())) {
            if (change.getFileType() == FileType.DIRECTORY) {
                continue;
            }
            String sourcePath = change.getNormalizedPath().replace('\\', '/');
            for (Path stale : outputsBySource.getOrDefault(sourcePath, List.of())) {
                Files.deleteIfExists(stale);
            }
            if (change.getChangeType() != ChangeType.REMOVED) {
                File file = change.getFile();
                inputs.add(new Translation.Input(sourcePath, file.getAbsolutePath(),
                        Files.readString(file.toPath(), StandardCharsets.UTF_8)));
            }
        }
        if (!inputs.isEmpty() && changes.isIncremental()) {
            addValueInferringFiles(inputs, outputsBySource);
        }
        List<Path> classpath = getClasspath().getFiles().stream().map(File::toPath).toList();
        List<Path> javaRoots = getJavaSourceDirs().getFiles().stream().map(File::toPath).toList();
        List<AxlDiagnostic> diagnostics = new ArrayList<>();
        for (Translator.Result result : Translation.run(inputs, options, classpath, javaRoots, out, getStrictNull().get())) {
            diagnostics.addAll(result.diagnostics());
        }
        int translated = inputs.size();
        getLogger().info("Translated {} .axl file(s)", translated);
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

    /** Files with inferred top-level types may depend on the files that changed, so they are redone too. */
    private void addValueInferringFiles(List<Translation.Input> inputs, Map<String, List<Path>> outputsBySource) {
        Set<String> changed = inputs.stream().map(Translation.Input::sourcePath).collect(Collectors.toSet());
        getSources().getAsFileTree().visit(details -> {
            String sourcePath = details.getRelativePath().getPathString();
            if (details.isDirectory() || changed.contains(sourcePath)) {
                return;
            }
            try {
                String text = Files.readString(details.getFile().toPath(), StandardCharsets.UTF_8);
                if (Translator.infersValueTypes(sourcePath, text)) {
                    for (Path stale : outputsBySource.getOrDefault(sourcePath, List.of())) {
                        Files.deleteIfExists(stale);
                    }
                    inputs.add(new Translation.Input(sourcePath, details.getFile().getAbsolutePath(), text));
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
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
