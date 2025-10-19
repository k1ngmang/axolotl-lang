package org.axl.maven;

import java.io.File;
import java.nio.file.Path;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

/** Translates `src/test/axl` to `target/generated-test-sources/axl`. */
@Mojo(name = "generate-test", defaultPhase = LifecyclePhase.GENERATE_TEST_SOURCES, threadSafe = true)
public class GenerateTestMojo extends AbstractGenerateMojo {

    @Parameter(defaultValue = "${project.basedir}/src/test/axl")
    File testSourceDirectory;

    @Parameter(defaultValue = "${project.build.directory}/generated-test-sources/axl")
    File testOutputDirectory;

    @Parameter(defaultValue = "${project.build.directory}/axl-test-state.json", readonly = true)
    File stateFile;

    @Override
    Path sourceDirectory() {
        return testSourceDirectory.toPath();
    }

    @Override
    Path outputDirectory() {
        return testOutputDirectory.toPath();
    }

    @Override
    Path stateFile() {
        return stateFile.toPath();
    }

    @Override
    void addSourceRoot(String path) {
        project.addTestCompileSourceRoot(path);
    }
}
