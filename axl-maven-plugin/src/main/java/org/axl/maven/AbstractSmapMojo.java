package org.axl.maven;

import java.io.IOException;
import java.nio.file.Path;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Parameter;
import org.axl.smap.SmapInjector;

/**
 * Rewrites classes compiled from generated sources to report .axl files and lines, and adds a JSR-45
 * SMAP (stratum `Axolotl`).
 */
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
            getLog().info("Rewrote " + changed + " class file(s) to .axl lines");
        } catch (IOException e) {
            throw new MojoExecutionException("Adding SMAP failed", e);
        }
    }
}
