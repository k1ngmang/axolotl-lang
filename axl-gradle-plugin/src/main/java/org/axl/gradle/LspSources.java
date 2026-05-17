package org.axl.gradle;

import java.io.File;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.component.ProjectComponentIdentifier;
import org.gradle.api.artifacts.result.ResolvedArtifactResult;
import org.gradle.api.file.SourceDirectorySet;
import org.gradle.api.tasks.SourceSet;
import org.gradle.api.tasks.SourceSetContainer;

/**
 * What the language server needs of a source set: its sources, then the sources of the source
 * sets and projects it compiles against, and the libraries. The language server reads the
 * sources of those, so that their edits are seen without a build and their old class files are
 * not, and takes their libraries too.
 */
final class LspSources {

    final Set<File> axl = new LinkedHashSet<>();
    final Set<File> java = new LinkedHashSet<>();
    final Set<File> classpath = new LinkedHashSet<>();
    private final Set<SourceSet> visited = new HashSet<>();

    private LspSources() {}

    static LspSources of(Project project, SourceSet sourceSet) {
        LspSources sources = new LspSources();
        sources.add(project, sourceSet);
        return sources;
    }

    private void add(Project project, SourceSet sourceSet) {
        if (!visited.add(sourceSet)) {
            return;
        }
        if (sourceSet.getExtensions().findByName("axl") instanceof SourceDirectorySet axlSources) {
            axl.addAll(axlSources.getSrcDirs());
        }
        File buildDir = project.getLayout().getBuildDirectory().get().getAsFile();
        sourceSet.getJava().getSrcDirs().stream().filter(dir -> !dir.toPath().startsWith(buildDir.toPath())).forEach(java::add);
        Set<File> libraries = new LinkedHashSet<>(sourceSet.getCompileClasspath().getFiles());
        SourceSet main = mainOf(project);
        if (main != null && main != sourceSet && libraries.containsAll(main.getOutput().getClassesDirs().getFiles())) {
            libraries.removeAll(main.getOutput().getFiles());
            add(project, main);
        }
        Configuration configuration = project.getConfigurations().getByName(sourceSet.getCompileClasspathConfigurationName());
        for (ResolvedArtifactResult artifact : configuration.getIncoming().getArtifacts()) {
            Project other = artifact.getId().getComponentIdentifier() instanceof ProjectComponentIdentifier id
                    ? project.findProject(id.getProjectPath()) : null;
            SourceSet otherMain = other == null ? null : mainOf(other);
            if (otherMain != null) {
                libraries.remove(artifact.getFile());
                add(other, otherMain);
            }
        }
        classpath.addAll(libraries);
    }

    private static SourceSet mainOf(Project project) {
        SourceSetContainer sourceSets = project.getExtensions().findByType(SourceSetContainer.class);
        return sourceSets == null ? null : sourceSets.findByName(SourceSet.MAIN_SOURCE_SET_NAME);
    }
}
