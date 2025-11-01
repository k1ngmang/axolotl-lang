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
import java.util.TreeMap;
import java.util.stream.Stream;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.axl.compiler.AxlDiagnostic;
import org.axl.compiler.GeneratedUnit;
import org.axl.compiler.Options;
import org.axl.compiler.Translator;
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

    abstract Path stateFile();

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
        if (!Files.isDirectory(sources)) {
            getLog().debug("No .axl sources in " + sources);
            return;
        }
        try {
            generate(sources, output);
        } catch (IOException e) {
            throw new MojoExecutionException("Axolotl generation failed", e);
        }
    }

    private void generate(Path sources, Path output) throws IOException, MojoFailureException {
        GenerationState previous = GenerationState.read(stateFile());
        GenerationState next = new GenerationState();
        Map<String, Path> current = axlFiles(sources);
        List<AxlDiagnostic> diagnostics = new ArrayList<>();
        for (Map.Entry<String, GenerationState.Entry> old : previous.files.entrySet()) {
            if (!current.containsKey(old.getKey())) {
                deleteOutputs(output, old.getValue());
            }
        }
        List<Translation.Input> inputs = new ArrayList<>();
        Map<String, String> hashes = new TreeMap<>();
        for (Map.Entry<String, Path> file : current.entrySet()) {
            String text = Files.readString(file.getValue(), StandardCharsets.UTF_8);
            String hash = sha256(text);
            GenerationState.Entry old = previous.files.get(file.getKey());
            if (old != null && old.hash.equals(hash) && outputsExist(output, old)) {
                next.files.put(file.getKey(), old);
                continue;
            }
            if (old != null) {
                deleteOutputs(output, old);
            }
            inputs.add(new Translation.Input(file.getKey(), file.getValue().toString(), text));
            hashes.put(file.getKey(), hash);
        }
        List<Path> generatedRoots = List.of(output.toAbsolutePath().normalize());
        List<Path> javaRoots = javaRoots().stream().map(Path::of)
                .filter(p -> !generatedRoots.contains(p.toAbsolutePath().normalize())).toList();
        List<Translator.Result> results;
        try {
            results = Translation.run(inputs, new Options(inlineElvis), classpath().stream().map(Path::of).toList(),
                    javaRoots, output);
        } catch (DependencyResolutionRequiredException e) {
            throw new IOException(e);
        }
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
