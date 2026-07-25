package org.axl.gradle;

import java.io.IOException;
import java.io.UncheckedIOException;
import org.axl.smap.SmapInjector;
import org.gradle.api.Action;
import org.gradle.api.Task;
import org.gradle.api.file.Directory;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.compile.JavaCompile;

/**
 * Runs as the last action of a compile task, so the classes it rewrites to .axl lines and gives a
 * SMAP stay outputs of that task and are cached that way.
 */
final class InjectSmap implements Action<Task> {

    private final Provider<Directory> generatedDir;
    private final Provider<Boolean> enabled;

    InjectSmap(Provider<Directory> generatedDir, Provider<Boolean> enabled) {
        this.generatedDir = generatedDir;
        this.enabled = enabled;
    }

    @Override
    public void execute(Task task) {
        if (!enabled.get()) {
            return;
        }
        JavaCompile compile = (JavaCompile) task;
        try {
            int changed = SmapInjector.inject(compile.getDestinationDirectory().get().getAsFile().toPath(),
                    generatedDir.get().getAsFile().toPath());
            task.getLogger().info("Rewrote {} class file(s) to .axl lines", changed);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
