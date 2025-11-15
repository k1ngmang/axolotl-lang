package org.axl.maven;

import java.io.File;
import java.nio.file.Path;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.artifact.DependencyResolutionRequiredException;
import java.util.List;

/** Translates `src/main/axl` to `target/generated-sources/axl`. */
@Mojo(name = "generate", defaultPhase = LifecyclePhase.GENERATE_SOURCES, threadSafe = true,
        requiresDependencyResolution = ResolutionScope.COMPILE)
public class GenerateMojo extends AbstractGenerateMojo {

    @Parameter(defaultValue = "${project.basedir}/src/main/axl")
    File sourceDirectory;

    @Parameter(defaultValue = "${project.build.directory}/generated-sources/axl")
    File outputDirectory;

    @Override
    Path sourceDirectory() {
        return sourceDirectory.toPath();
    }

    @Override
    Path outputDirectory() {
        return outputDirectory.toPath();
    }

    @Override
    void addSourceRoot(String path) {
        project.addCompileSourceRoot(path);
    }

    @Override
    List<String> classpath() throws DependencyResolutionRequiredException {
        return project.getCompileClasspathElements();
    }

    @Override
    List<String> javaRoots() {
        return project.getCompileSourceRoots();
    }
}
