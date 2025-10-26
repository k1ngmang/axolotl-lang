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

/** Writes `target/axl/axl.json`, from which the language server takes the source roots and classpath. */
@Mojo(name = "lsp-config", requiresDependencyResolution = ResolutionScope.TEST, threadSafe = true)
public class LspConfigMojo extends AbstractMojo {

    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    MavenProject project;

    @Parameter(defaultValue = "${project.basedir}/src/main/axl")
    File sourceDirectory;

    @Parameter(defaultValue = "${project.basedir}/src/test/axl")
    File testSourceDirectory;

    @Parameter(defaultValue = "${project.build.directory}/axl/axl.json")
    File outputFile;

    @Override
    public void execute() throws MojoExecutionException {
        try {
            Map<String, List<String>> config = new LinkedHashMap<>();
            config.put("sourceRoots", List.of(sourceDirectory.getAbsolutePath(), testSourceDirectory.getAbsolutePath()));
            List<String> javaRoots = new ArrayList<>();
            String build = project.getBuild().getDirectory();
            for (String root : concat(project.getCompileSourceRoots(), project.getTestCompileSourceRoots())) {
                if (!root.startsWith(build)) {
                    javaRoots.add(root);
                }
            }
            config.put("javaSourceRoots", javaRoots);
            config.put("classpath", project.getTestClasspathElements());
            Path output = outputFile.toPath();
            Files.createDirectories(output.getParent());
            Files.writeString(output, new GsonBuilder().setPrettyPrinting().create().toJson(config) + "\n",
                    StandardCharsets.UTF_8);
            getLog().info("Wrote " + output);
        } catch (IOException | DependencyResolutionRequiredException e) {
            throw new MojoExecutionException("Writing axl.json failed", e);
        }
    }

    private static List<String> concat(List<String> a, List<String> b) {
        List<String> result = new ArrayList<>(a);
        result.addAll(b);
        return result;
    }
}
