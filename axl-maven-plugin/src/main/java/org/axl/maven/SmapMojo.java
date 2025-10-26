package org.axl.maven;

import java.io.File;
import java.nio.file.Path;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

/** Adds SMAPs to `target/classes`, so debuggers and stack traces can show .axl lines. */
@Mojo(name = "smap", defaultPhase = LifecyclePhase.PROCESS_CLASSES, threadSafe = true)
public class SmapMojo extends AbstractSmapMojo {

    @Parameter(defaultValue = "${project.build.outputDirectory}")
    File classesDirectory;

    @Parameter(defaultValue = "${project.build.directory}/generated-sources/axl")
    File outputDirectory;

    @Override
    Path classesDirectory() {
        return classesDirectory.toPath();
    }

    @Override
    Path generatedDirectory() {
        return outputDirectory.toPath();
    }
}
