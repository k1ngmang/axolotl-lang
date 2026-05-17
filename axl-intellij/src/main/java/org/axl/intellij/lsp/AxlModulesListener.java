package org.axl.intellij.lsp;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ModuleRootEvent;
import com.intellij.openapi.roots.ModuleRootListener;
import com.intellij.platform.lsp.api.LspServerManager;
import org.jetbrains.annotations.NotNull;

/** Restarts the language server when the modules change, such as after a Gradle sync: it gets them when it starts. */
public final class AxlModulesListener implements ModuleRootListener {

    private final Project project;

    public AxlModulesListener(Project project) {
        this.project = project;
    }

    @Override
    public void rootsChanged(@NotNull ModuleRootEvent event) {
        LspServerManager.getInstance(project).stopAndRestartIfNeeded(AxlLspServerSupportProvider.class);
    }
}
