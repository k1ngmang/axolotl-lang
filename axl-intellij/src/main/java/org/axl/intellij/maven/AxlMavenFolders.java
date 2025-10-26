package org.axl.intellij.maven;

import java.util.stream.Stream;
import org.jetbrains.idea.maven.importing.MavenWorkspaceConfigurator;
import org.jetbrains.idea.maven.project.MavenProject;
import org.jetbrains.annotations.NotNull;

/** Marks `src/main/axl` and `src/test/axl` as source roots in Maven projects that use axl-maven-plugin. */
public final class AxlMavenFolders implements MavenWorkspaceConfigurator {

    @Override
    public @NotNull Stream<AdditionalFolder> getAdditionalFolders(@NotNull FoldersContext context) {
        MavenProject project = context.getMavenProject();
        if (project.findPlugin("org.axl", "axl-maven-plugin") == null) {
            return Stream.empty();
        }
        String dir = project.getDirectory();
        return Stream.of(new AdditionalFolder(dir + "/src/main/axl", FolderType.SOURCE),
                new AdditionalFolder(dir + "/src/test/axl", FolderType.TEST_SOURCE));
    }
}
