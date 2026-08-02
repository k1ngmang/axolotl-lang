package org.axl.maven;

import java.io.File;
import java.nio.file.Path;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.artifact.DependencyResolutionRequiredException;
import java.util.List;

/** Translates `src/test/axl` to `target/generated-test-sources/axl`. */
@Mojo(name = "generate-test", defaultPhase = LifecyclePhase.GENERATE_TEST_SOURCES, threadSafe = true,
        requiresDependencyResolution = ResolutionScope.TEST)
public class GenerateTestMojo extends AbstractGenerateMojo {

    /** The main sources, whose declarations the tests read. */
    @Parameter(defaultValue = "${project.basedir}/src/main/axl")
    File sourceDirectory;

    @Parameter(defaultValue = "${project.basedir}/src/test/axl")
    File testSourceDirectory;

    @Parameter(defaultValue = "${project.build.directory}/generated-test-sources/axl")
    File testOutputDirectory;

    @Override
    Path sourceDirectory() {
        return testSourceDirectory.toPath();
    }

    @Override
    Path outputDirectory() {
        return testOutputDirectory.toPath();
    }

    @Override
    void addSourceRoot(String path) {
        project.addTestCompileSourceRoot(path);
    }

    @Override
    List<String> classpath() throws DependencyResolutionRequiredException {
        return project.getTestClasspathElements();
    }

    @Override
    List<String> javaRoots() {
        return concat(project.getCompileSourceRoots(), project.getTestCompileSourceRoots());
    }

    @Override
    List<Path> contextDirectories() {
        return List.of(sourceDirectory.toPath());
    }

    private static List<String> concat(List<String> a, List<String> b) {
        List<String> all = new java.util.ArrayList<>(a);
        all.addAll(b);
        return all;
    }
}
