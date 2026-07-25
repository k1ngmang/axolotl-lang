package org.axl.maven;

import java.io.File;
import java.nio.file.Path;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

/** Makes the classes in `target/classes` report .axl files and lines in stack traces and debuggers. */
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
