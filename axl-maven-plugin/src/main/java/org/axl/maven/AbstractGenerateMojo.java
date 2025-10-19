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

/** Translates changed .axl files of one source directory and registers the output as a source root. */
abstract class AbstractGenerateMojo extends AbstractMojo {

    /** Project property holding the `-Xplugin:AxlDiagnostics ...` javac argument for `compilerArgs`. */
    static final String DIAGNOSTICS_PROPERTY = "axl.javacPlugin";

    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    MavenProject project;

    /** Expand statement-level `?:` without a lambda. */
    @Parameter(property = "axl.inlineElvis", defaultValue = "false")
    boolean inlineElvis;

    /** `--release` for compilation; sets `maven.compiler.release` unless the project defines it. */
    @Parameter(property = "axl.javaRelease", defaultValue = "21")
    int javaRelease;

    @Parameter(property = "axl.skip", defaultValue = "false")
    boolean skip;

    abstract Path sourceDirectory();

    abstract Path outputDirectory();

    abstract Path stateFile();

    abstract void addSourceRoot(String path);

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
        int translated = 0;
        for (Map.Entry<String, GenerationState.Entry> old : previous.files.entrySet()) {
            if (!current.containsKey(old.getKey())) {
                deleteOutputs(output, old.getValue());
            }
        }
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
            Translator.Result result = Translator.translate(file.getKey(), file.getValue().toString(), text,
                    new Options(inlineElvis));
            diagnostics.addAll(result.diagnostics());
            translated++;
            GeneratedUnit unit = result.unit();
            if (unit != null) {
                unit.writeTo(output);
                next.files.put(file.getKey(), new GenerationState.Entry(hash, List.of(unit.javaPath(), unit.mapPath())));
            }
        }
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
            project.getProperties().setProperty(DIAGNOSTICS_PROPERTY, option + " " + root);
        }
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
