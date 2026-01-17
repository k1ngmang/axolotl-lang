package org.axl.gradle;

import java.io.File;
import java.nio.file.Path;
import java.util.List;
import org.axl.javac.DiagnosticsPlugin;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.Internal;
import org.gradle.process.CommandLineArgumentProvider;

/**
 * Enables the AxlDiagnostics javac plugin from axl-compiler-javac, which reports errors in
 * generated files at their .axl lines. The directories are not inputs: they only affect messages.
 * Strict null mode adds errors, so it is one.
 */
public abstract class DiagnosticsArguments implements CommandLineArgumentProvider {

    @Internal
    public abstract DirectoryProperty getGeneratedDir();

    @Internal
    public abstract ConfigurableFileCollection getSourceDirs();

    @Input
    public abstract Property<Boolean> getStrictNull();

    @Override
    public Iterable<String> asArguments() {
        StringBuilder option = new StringBuilder("-Xplugin:AxlDiagnostics");
        Path generated = getGeneratedDir().get().getAsFile().toPath();
        for (File sources : getSourceDirs()) {
            option.append(' ').append(new DiagnosticsPlugin.Root(generated, sources.toPath()).argument());
        }
        if (getStrictNull().get()) {
            option.append(" strict");
        }
        return List.of(option.toString());
    }
}
