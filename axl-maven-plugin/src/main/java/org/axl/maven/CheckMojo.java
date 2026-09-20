package org.axl.maven;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.apache.maven.artifact.DependencyResolutionRequiredException;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;
import org.axl.compiler.AxlDiagnostic;
import org.axl.javac.DiagnosticTranslator;
import org.axl.javac.GeneratedSource;
import org.axl.javac.GeneratedSources;
import org.axl.javac.JavaCompilation;

/**
 * Compiles the main sources again, with Error Prone and NullAway, and prints every error of a
 * generated file at its .axl line. Useful when `maven-compiler-plugin` is not configured with the
 * AxlDiagnostics javac plugin. Run it after `generate`, for example `mvn generate-sources axl:check`.
 */
@Mojo(name = "check", requiresDependencyResolution = ResolutionScope.COMPILE, threadSafe = true)
public class CheckMojo extends AbstractMojo {

    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    MavenProject project;

    @Parameter(defaultValue = "${project.basedir}/src/main/axl")
    File sourceDirectory;

    @Parameter(defaultValue = "${project.build.directory}/generated-sources/axl")
    File outputDirectory;

    /** Run Error Prone and NullAway; needs the `--add-exports` options in `.mvn/jvm.config`. */
    @Parameter(property = "axl.nullAway", defaultValue = "true")
    boolean nullAway;

    /** Run Error Prone and NullAway on the hand-written Java too; by default they check only the generated files. */
    @Parameter(property = "axl.checkJava", defaultValue = "false")
    boolean checkJava;

    /** Treat values from Java code without nullness annotations as nullable. */
    @Parameter(property = "axl.strictNull", defaultValue = "false")
    boolean strictNull;

    @Parameter(property = "axl.skip", defaultValue = "false")
    boolean skip;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        if (skip) {
            return;
        }
        try {
            List<GeneratedSource> generated = GeneratedSources.load(outputDirectory.toPath(), sourceDirectory.toPath(),
                    Path::toString);
            List<Path> classpath = project.getCompileClasspathElements().stream().map(Path::of).toList();
            // Inside target, so that `mvn clean` removes it; it is not the real output directory.
            Path classes = Files.createDirectories(Path.of(project.getBuild().getDirectory(), "axl-check-classes"));
            List<Path> sources = javaSources();
            Path generatedRoot = outputDirectory.toPath().toAbsolutePath().normalize();
            List<Path> unchecked = checkJava ? List.of()
                    : sources.stream().filter(p -> !p.toAbsolutePath().normalize().startsWith(generatedRoot)).toList();
            JavaCompilation.Outcome outcome = JavaCompilation.compile(sources, classpath, classes, List.of(), nullAway, strictNull, unchecked);
            var translator = new DiagnosticTranslator(generated, Path::toString).classpath(classpath);
            boolean failed = false;
            for (var d : outcome.diagnostics()) {
                AxlDiagnostic translated = translator.translate(d);
                if (translated == null) {
                    continue;
                }
                if (translated.isError()) {
                    getLog().error(translated.formatWithCode());
                    failed = true;
                } else {
                    getLog().warn(translated.formatWithCode());
                }
            }
            if (failed || !outcome.success()) {
                throw new MojoFailureException("Compilation failed; see the errors above.");
            }
        } catch (IllegalStateException e) {
            throw new MojoExecutionException(e.getMessage() + " (add them to .mvn/jvm.config or run with -Daxl.nullAway=false)", e);
        } catch (IOException | DependencyResolutionRequiredException e) {
            throw new MojoExecutionException("axl:check failed", e);
        }
    }

    private List<Path> javaSources() throws IOException {
        List<Path> sources = new ArrayList<>();
        List<String> roots = new ArrayList<>(project.getCompileSourceRoots());
        if (!roots.contains(outputDirectory.getPath())) {
            roots.add(outputDirectory.getPath());
        }
        for (String root : roots) {
            Path dir = Path.of(root);
            if (!Files.isDirectory(dir)) {
                continue;
            }
            try (Stream<Path> files = Files.walk(dir)) {
                files.filter(p -> p.toString().endsWith(".java")).sorted().forEach(sources::add);
            }
        }
        return sources;
    }
}
