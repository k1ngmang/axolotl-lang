package org.axl.gradle;

import java.util.List;
import org.axl.javac.JavaCompilation;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.Internal;
import org.gradle.process.CommandLineArgumentProvider;

/**
 * Keeps Error Prone and NullAway to the Java translated from .axl files: the hand-written Java of a mixed
 * module keeps the warnings of plain javac, which a migration would otherwise drown in. The directory is
 * not an input, so that the build cache stays relocatable; an `excludedPaths` of the build's own wins.
 */
public abstract class GeneratedOnlyArguments implements CommandLineArgumentProvider {

    @Internal
    public abstract DirectoryProperty getGeneratedDir();

    @Input
    public abstract Property<Boolean> getCheckJava();

    @Internal
    public abstract Property<String> getExcludedPaths();

    @Override
    public Iterable<String> asArguments() {
        if (getCheckJava().get() || getExcludedPaths().isPresent()) {
            return List.of();
        }
        return List.of("-XepExcludedPaths:" + JavaCompilation.outsidePattern(List.of(getGeneratedDir().get().getAsFile().toPath())));
    }
}
