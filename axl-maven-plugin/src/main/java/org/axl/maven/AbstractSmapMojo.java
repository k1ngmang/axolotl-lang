package org.axl.maven;

import java.io.IOException;
import java.nio.file.Path;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Parameter;
import org.axl.smap.SmapInjector;

/** Adds a JSR-45 SMAP (stratum `Axolotl`) to classes compiled from generated sources. */
abstract class AbstractSmapMojo extends AbstractMojo {

    @Parameter(property = "axl.smap", defaultValue = "true")
    boolean smap;

    @Parameter(property = "axl.skip", defaultValue = "false")
    boolean skip;

    abstract Path classesDirectory();

    abstract Path generatedDirectory();

    @Override
    public void execute() throws MojoExecutionException {
        if (skip || !smap) {
            return;
        }
        try {
            int changed = SmapInjector.inject(classesDirectory(), generatedDirectory());
            getLog().info("Added SMAP to " + changed + " class file(s)");
        } catch (IOException e) {
            throw new MojoExecutionException("Adding SMAP failed", e);
        }
    }
}
