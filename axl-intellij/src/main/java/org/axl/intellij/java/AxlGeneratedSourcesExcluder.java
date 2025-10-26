package org.axl.intellij.java;

import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ModuleRootManager;
import com.intellij.openapi.roots.impl.DirectoryIndexExcludePolicy;
import com.intellij.openapi.vfs.VirtualFile;
import java.util.ArrayList;
import java.util.List;
import org.jetbrains.annotations.NotNull;

/**
 * Hides the Java the build generates from .axl files: the IDE uses the .axl sources directly, and
 * the generated copies would clash with them.
 */
public final class AxlGeneratedSourcesExcluder implements DirectoryIndexExcludePolicy {

    private static final List<String> GENERATED = List.of("build/generated/axl", "target/generated-sources/axl",
            "target/generated-test-sources/axl");

    private final Project project;

    public AxlGeneratedSourcesExcluder(Project project) {
        this.project = project;
    }

    @Override
    public String @NotNull [] getExcludeUrlsForProject() {
        List<String> urls = new ArrayList<>();
        for (Module module : ModuleManager.getInstance(project).getModules()) {
            for (VirtualFile root : ModuleRootManager.getInstance(module).getContentRoots()) {
                for (String path : GENERATED) {
                    urls.add(root.getUrl() + "/" + path);
                }
            }
        }
        return urls.toArray(String[]::new);
    }
}
