package org.axl.maven;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.axl.compiler.AxlDiagnostic;
import org.axl.compiler.ErrorCode;
import org.axl.compiler.GeneratedUnit;
import org.axl.compiler.Options;
import org.axl.compiler.Translator;
import org.axl.compiler.ast.SourceFile;
import org.axl.compiler.parse.SourceParser;
import org.axl.javac.Translation;
import org.apache.maven.artifact.DependencyResolutionRequiredException;

/** Translates changed .axl files of one source directory and registers the output as a source root. */
abstract class AbstractGenerateMojo extends AbstractMojo {

    /** Project property holding the `-Xplugin:AxlDiagnostics ...` javac argument for `compilerArgs`. */
    static final String DIAGNOSTICS_PROPERTY = "axl.javacPlugin";

    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    MavenProject project;

    /** Expand statement-level `?:` without a lambda. */
    @Parameter(property = "axl.inlineElvis", defaultValue = "false")
    boolean inlineElvis;

    /** Treat values from Java code without nullness annotations as nullable. */
    @Parameter(property = "axl.strictNull", defaultValue = "false")
    boolean strictNull;

    /** `--release` for compilation; sets `maven.compiler.release` unless the project defines it. */
    @Parameter(property = "axl.javaRelease", defaultValue = "21")
    int javaRelease;

    @Parameter(property = "axl.skip", defaultValue = "false")
    boolean skip;

    abstract Path sourceDirectory();

    abstract Path outputDirectory();

    /**
     * Kept out of the output directory, which goes into the sources jar, and named after both
     * directories, so that executions sharing an output directory do not delete each other's files.
     */
    Path stateFile() {
        Path build = Path.of(project.getBuild().getDirectory()).toAbsolutePath().normalize();
        String name = fileName(build, outputDirectory()) + "-from-" + fileName(build.getParent(), sourceDirectory());
        return build.resolve("axl").resolve(name + ".state.json");
    }

    private static String fileName(Path base, Path dir) {
        Path absolute = dir.toAbsolutePath().normalize();
        Path name = absolute.startsWith(base) ? base.relativize(absolute) : absolute;
        return name.toString().replaceAll("[/\\\\:]", "_");
    }

    @Parameter(defaultValue = "${plugin.version}", readonly = true)
    String pluginVersion;

    abstract void addSourceRoot(String path);

    /** Classpath and Java source roots, for the types of top-level values that depend on them. */
    abstract List<String> classpath() throws DependencyResolutionRequiredException;

    abstract List<String> javaRoots();

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        if (skip) {
            getLog().info("Skipping Axolotl generation");
            return;
        }
        if (project.getProperties().getProperty("maven.compiler.release") == null) {
            project.getProperties().setProperty("maven.compiler.release", Integer.toString(javaRelease));
        }
        Path sources = sourceDirectory();
        Path output = outputDirectory();
        addSourceRoot(output.toString());
        addDiagnosticsRoot(output, sources);
        try {
            if (Files.isDirectory(sources)) {
                generate(sources, output);
            } else {
                getLog().debug("No .axl sources in " + sources);
                deleteGenerated(output);
            }
        } catch (IOException e) {
            throw new MojoExecutionException("Axolotl generation failed", e);
        }
    }

    /** Without the source directory, what earlier runs generated from it must not be compiled. */
    private void deleteGenerated(Path output) throws IOException {
        for (GenerationState.Entry entry : GenerationState.read(stateFile()).files.values()) {
            deleteOutputs(output, entry);
        }
        Files.deleteIfExists(stateFile());
    }

    private void generate(Path sources, Path output) throws IOException, MojoFailureException {
        GenerationState previous = GenerationState.read(stateFile());
        GenerationState next = new GenerationState();
        Map<String, Path> current = axlFiles(sources);
        List<Path> generatedRoots = List.of(output.toAbsolutePath().normalize());
        List<Path> javaRoots = javaRoots().stream().map(Path::of)
                .filter(p -> !generatedRoots.contains(p.toAbsolutePath().normalize())).toList();
        List<Path> classpath;
        try {
            classpath = classpath().stream().map(Path::of).toList();
        } catch (DependencyResolutionRequiredException e) {
            throw new IOException(e);
        }
        // Options, the plugin and the code inferred types depend on: when they change, everything is redone.
        next.environment = environment(classpath, javaRoots);
        boolean sameEnvironment = next.environment.equals(previous.environment);
        List<AxlDiagnostic> diagnostics = new ArrayList<>();
        boolean removed = false;
        for (Map.Entry<String, GenerationState.Entry> old : previous.files.entrySet()) {
            if (!current.containsKey(old.getKey())) {
                deleteOutputs(output, old.getValue());
                removed = true;
            }
        }
        Map<String, String> texts = readAll(current);
        Map<String, String> duplicates = duplicateClasses(current, texts);
        List<Translation.Input> inputs = new ArrayList<>();
        Map<String, String> hashes = new TreeMap<>();
        for (Map.Entry<String, Path> file : current.entrySet()) {
            String text = texts.get(file.getKey());
            String hash = sha256(text);
            GenerationState.Entry old = previous.files.get(file.getKey());
            if (duplicates.containsKey(file.getKey())) {
                if (old != null) {
                    deleteOutputs(output, old);
                }
                diagnostics.add(AxlDiagnostic.error(file.getValue().toString(), 1, 1, ErrorCode.DUPLICATE_CLASS,
                        duplicates.get(file.getKey())));
                continue;
            }
            if (sameEnvironment && old != null && old.hash.equals(hash) && outputsExist(output, old)) {
                next.files.put(file.getKey(), old);
                continue;
            }
            if (old != null) {
                deleteOutputs(output, old);
            }
            inputs.add(new Translation.Input(file.getKey(), file.getValue().toString(), text));
            hashes.put(file.getKey(), hash);
        }
        if (!inputs.isEmpty() || removed) {
            addValueInferringFiles(current, texts, inputs, hashes, next, output);
        }
        List<Translator.Result> results = Translation.run(inputs, new Options(inlineElvis), classpath, javaRoots, output,
                strictNull);
        for (int i = 0; i < results.size(); i++) {
            Translator.Result result = results.get(i);
            diagnostics.addAll(result.diagnostics());
            GeneratedUnit unit = result.unit();
            if (unit != null) {
                String source = inputs.get(i).sourcePath();
                next.files.put(source, new GenerationState.Entry(hashes.get(source), List.of(unit.javaPath(), unit.mapPath())));
            }
        }
        int translated = inputs.size();
        next.write(stateFile());
        getLog().info("Translated " + translated + " of " + current.size() + " .axl file(s)");
        report(diagnostics);
    }

    private void report(List<AxlDiagnostic> diagnostics) throws MojoFailureException {
        for (AxlDiagnostic d : diagnostics) {
            if (d.isError()) {
                getLog().error(d.format());
            } else {
                getLog().warn(d.format());
            }
        }
        if (diagnostics.stream().anyMatch(AxlDiagnostic::isError)) {
            throw new MojoFailureException("Axolotl translation failed; see the errors above.");
        }
    }

    private static Map<String, String> readAll(Map<String, Path> files) throws IOException {
        Map<String, String> texts = new TreeMap<>();
        for (Map.Entry<String, Path> file : files.entrySet()) {
            texts.put(file.getKey(), Files.readString(file.getValue(), StandardCharsets.UTF_8));
        }
        return texts;
    }

    /** Files with inferred top-level types may depend on the files that changed or were removed, so they are redone too. */
    private void addValueInferringFiles(Map<String, Path> current, Map<String, String> texts, List<Translation.Input> inputs,
                                        Map<String, String> hashes, GenerationState next, Path output) throws IOException {
        for (String sourcePath : List.copyOf(next.files.keySet())) {
            String text = texts.get(sourcePath);
            if (Translator.infersValueTypes(sourcePath, text)) {
                deleteOutputs(output, next.files.remove(sourcePath));
                inputs.add(new Translation.Input(sourcePath, current.get(sourcePath).toString(), text));
                hashes.put(sourcePath, sha256(text));
            }
        }
    }

    /**
     * Files that declare the same class, with the error for each. Two such files overwrite each
     * other's Java, and only all files together show it, not just the changed ones.
     */
    private static Map<String, String> duplicateClasses(Map<String, Path> current, Map<String, String> texts) {
        Map<String, List<String>> byClass = new TreeMap<>();
        texts.forEach((sourcePath, text) -> {
            SourceFile file = SourceParser.parse(sourcePath, Translator.className(sourcePath), text).file();
            if (file != null) {
                String name = file.packageName() == null || file.packageName().isEmpty()
                        ? file.className() : file.packageName() + "." + file.className();
                byClass.computeIfAbsent(name, k -> new ArrayList<>()).add(sourcePath);
            }
        });
        Map<String, String> result = new TreeMap<>();
        byClass.forEach((name, sourcePaths) -> {
            if (sourcePaths.size() > 1) {
                String files = sourcePaths.stream().map(p -> current.get(p).toString()).collect(Collectors.joining(", "));
                sourcePaths.forEach(p -> result.put(p, "the class `" + name + "` is declared by several files: " + files));
            }
        });
        return result;
    }

    private String environment(List<Path> classpath, List<Path> javaRoots) throws IOException {
        StringBuilder sb = new StringBuilder("inlineElvis=" + inlineElvis + " strictNull=" + strictNull + " plugin=" + pluginVersion);
        List<Path> ownOutputs = ownOutputDirectories();
        for (Path entry : classpath) {
            if (ownOutputs.contains(entry.toAbsolutePath().normalize())) {
                continue;
            }
            sb.append('\n').append(entry);
            if (Files.isDirectory(entry)) {
                appendFiles(sb, entry, ".class");
            } else {
                sb.append(' ').append(Files.exists(entry) ? Files.getLastModifiedTime(entry) : "-");
            }
        }
        for (Path root : javaRoots) {
            sb.append('\n').append(root);
            if (Files.isDirectory(root)) {
                appendFiles(sb, root, ".java");
            }
        }
        return sha256(sb.toString());
    }

    /** The time of a directory does not change when a file in a subdirectory does, so the files are listed. */
    private static void appendFiles(StringBuilder sb, Path dir, String extension) throws IOException {
        try (Stream<Path> files = Files.walk(dir)) {
            for (Path file : files.filter(p -> p.toString().endsWith(extension)).sorted().toList()) {
                sb.append('\n').append(dir.relativize(file)).append(' ').append(Files.getLastModifiedTime(file))
                        .append(' ').append(Files.size(file));
            }
        }
    }

    /**
     * The classes this module compiles: missing after a clean and present after the build, they would
     * make every second build translate everything again. Its Java sources are hashed instead.
     */
    private List<Path> ownOutputDirectories() {
        return Stream.of(project.getBuild().getOutputDirectory(), project.getBuild().getTestOutputDirectory())
                .filter(Objects::nonNull)
                .map(dir -> Path.of(dir).toAbsolutePath().normalize())
                .toList();
    }

    /** Collects every root as `generated=sources` in one javac plugin argument. */
    private void addDiagnosticsRoot(Path output, Path sources) {
        String root = output.toUri() + "=" + sources.toUri();
        String option = project.getProperties().getProperty(DIAGNOSTICS_PROPERTY, "-Xplugin:AxlDiagnostics");
        if (!option.contains(root)) {
            option += " " + root;
        }
        if (strictNull && !option.endsWith(" strict") && !option.contains(" strict ")) {
            option += " strict";
        }
        project.getProperties().setProperty(DIAGNOSTICS_PROPERTY, option);
    }

    private static Map<String, Path> axlFiles(Path sources) throws IOException {
        Map<String, Path> result = new TreeMap<>();
        try (Stream<Path> files = Files.walk(sources)) {
            files.filter(p -> p.toString().endsWith(".axl") && Files.isRegularFile(p))
                    .forEach(p -> result.put(sources.relativize(p).toString().replace('\\', '/'), p));
        }
        return result;
    }

    private static boolean outputsExist(Path output, GenerationState.Entry entry) {
        return entry.outputs.stream().allMatch(o -> Files.exists(output.resolve(o)));
    }

    private static void deleteOutputs(Path output, GenerationState.Entry entry) throws IOException {
        for (String o : entry.outputs) {
            Files.deleteIfExists(output.resolve(o));
        }
    }

    private static String sha256(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
