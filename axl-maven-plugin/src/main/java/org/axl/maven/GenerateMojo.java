package org.axl.maven;

import java.io.File;
import java.nio.file.Path;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

/** Translates `src/main/axl` to `target/generated-sources/axl`. */
@Mojo(name = "generate", defaultPhase = LifecyclePhase.GENERATE_SOURCES, threadSafe = true)
public class GenerateMojo extends AbstractGenerateMojo {

    @Parameter(defaultValue = "${project.basedir}/src/main/axl")
    File sourceDirectory;

    @Parameter(defaultValue = "${project.build.directory}/generated-sources/axl")
    File outputDirectory;

    @Parameter(defaultValue = "${project.build.directory}/axl-state.json", readonly = true)
    File stateFile;

    @Override
    Path sourceDirectory() {
        return sourceDirectory.toPath();
    }

    @Override
    Path outputDirectory() {
        return outputDirectory.toPath();
    }

    @Override
    Path stateFile() {
        return stateFile.toPath();
    }

    @Override
    void addSourceRoot(String path) {
        project.addCompileSourceRoot(path);
    }
}
