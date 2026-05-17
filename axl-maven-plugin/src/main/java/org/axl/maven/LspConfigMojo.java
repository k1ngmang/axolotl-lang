package org.axl.maven;

import com.google.gson.GsonBuilder;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.maven.artifact.DependencyResolutionRequiredException;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;

/**
 * Writes `target/axl/axl.json`, from which the language server takes its modules: the main sources,
 * and the test sources, which see the main ones. The project's own class directories are left out
 * of the classpaths, since the sources stand for them.
 */
@Mojo(name = "lsp-config", requiresDependencyResolution = ResolutionScope.TEST, threadSafe = true)
public class LspConfigMojo extends AbstractMojo {

    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    MavenProject project;

    @Parameter(defaultValue = "${project.basedir}/src/main/axl")
    File sourceDirectory;

    @Parameter(defaultValue = "${project.basedir}/src/test/axl")
    File testSourceDirectory;

    @Parameter(property = "axl.strictNull", defaultValue = "false")
    boolean strictNull;

    @Parameter(defaultValue = "${project.build.directory}/axl/axl.json")
    File outputFile;

    @Override
    public void execute() throws MojoExecutionException {
        try {
            List<String> mainJava = handWritten(project.getCompileSourceRoots());
            List<String> testJava = concat(handWritten(project.getTestCompileSourceRoots()), mainJava);
            Map<String, Object> main = module(List.of(sourceDirectory.getAbsolutePath()), mainJava,
                    project.getCompileClasspathElements());
            Map<String, Object> test = module(List.of(testSourceDirectory.getAbsolutePath(), sourceDirectory.getAbsolutePath()),
                    testJava, project.getTestClasspathElements());
            Map<String, Object> config = Map.of("modules", List.of(main, test));
            Path output = outputFile.toPath();
            Files.createDirectories(output.getParent());
            Files.writeString(output, new GsonBuilder().setPrettyPrinting().create().toJson(config) + "\n",
                    StandardCharsets.UTF_8);
            getLog().info("Wrote " + output);
        } catch (IOException | DependencyResolutionRequiredException e) {
            throw new MojoExecutionException("Writing axl.json failed", e);
        }
    }

    private Map<String, Object> module(List<String> sourceRoots, List<String> javaRoots, List<String> classpath) {
        List<String> classes = List.of(project.getBuild().getOutputDirectory(), project.getBuild().getTestOutputDirectory());
        Map<String, Object> module = new LinkedHashMap<>();
        module.put("sourceRoots", sourceRoots);
        module.put("javaSourceRoots", javaRoots);
        module.put("classpath", classpath.stream().filter(entry -> !classes.contains(entry)).toList());
        module.put("strictNull", strictNull);
        return module;
    }

    /** Without the roots of generated sources, such as the Java that `axl:generate` writes. */
    private List<String> handWritten(List<String> roots) {
        String build = project.getBuild().getDirectory();
        return roots.stream().filter(root -> !root.startsWith(build)).toList();
    }

    private static List<String> concat(List<String> a, List<String> b) {
        List<String> result = new ArrayList<>(a);
        result.addAll(b);
        return result;
    }
}
