package org.axl.maven;

import java.io.File;
import java.nio.file.Path;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

/** Adds SMAPs to `target/test-classes`. */
@Mojo(name = "smap-test", defaultPhase = LifecyclePhase.PROCESS_TEST_CLASSES, threadSafe = true)
public class SmapTestMojo extends AbstractSmapMojo {

    @Parameter(defaultValue = "${project.build.testOutputDirectory}")
    File testClassesDirectory;

    @Parameter(defaultValue = "${project.build.directory}/generated-test-sources/axl")
    File testOutputDirectory;

    @Override
    Path classesDirectory() {
        return testClassesDirectory.toPath();
    }

    @Override
    Path generatedDirectory() {
        return testOutputDirectory.toPath();
    }
}
