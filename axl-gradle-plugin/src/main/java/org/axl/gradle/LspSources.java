package org.axl.gradle;

import java.io.File;
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
 * What the language server needs of a source set: its sources, its compile classpath as Gradle
 * gives it, and the sources of the source sets and projects it compiles against. Their .axl sources
 * are translated with this one, so that their edits are seen without a build; their Java sources
 * are only read where newer than their classes, since they need their own libraries, Kotlin or
 * annotation processors to compile.
 */
final class LspSources {

    final Set<File> axl = new LinkedHashSet<>();
    final Set<File> java = new LinkedHashSet<>();
    final Set<File> javaSourcepath = new LinkedHashSet<>();
    final Set<File> classpath = new LinkedHashSet<>();

    private LspSources() {}

    static LspSources of(Project project, SourceSet sourceSet) {
        LspSources sources = new LspSources();
        sources.axl.addAll(axlDirs(sourceSet));
        sources.java.addAll(javaDirs(project, sourceSet));
        sources.classpath.addAll(sourceSet.getCompileClasspath().getFiles());
        SourceSet main = mainOf(project);
        if (main != null && main != sourceSet && sources.classpath.containsAll(main.getOutput().getClassesDirs().getFiles())) {
            sources.addDependency(project, main);
        }
        Configuration configuration = project.getConfigurations().getByName(sourceSet.getCompileClasspathConfigurationName());
        for (ResolvedArtifactResult artifact : configuration.getIncoming().getArtifacts()) {
            Project other = artifact.getId().getComponentIdentifier() instanceof ProjectComponentIdentifier id
                    ? project.findProject(id.getProjectPath()) : null;
            SourceSet otherMain = other == null ? null : mainOf(other);
            if (otherMain != null) {
                sources.addDependency(other, otherMain);
            }
        }
        return sources;
    }

    private void addDependency(Project project, SourceSet sourceSet) {
        axl.addAll(axlDirs(sourceSet));
        javaSourcepath.addAll(javaDirs(project, sourceSet));
    }

    private static Set<File> axlDirs(SourceSet sourceSet) {
        return sourceSet.getExtensions().findByName("axl") instanceof SourceDirectorySet axlSources ? axlSources.getSrcDirs() : Set.of();
    }

    /** Without the generated directories in the build directory, such as the Java translated from .axl files. */
    private static Set<File> javaDirs(Project project, SourceSet sourceSet) {
        File buildDir = project.getLayout().getBuildDirectory().get().getAsFile();
        Set<File> result = new LinkedHashSet<>();
        sourceSet.getJava().getSrcDirs().stream().filter(dir -> !dir.toPath().startsWith(buildDir.toPath())).forEach(result::add);
        return result;
    }

    private static SourceSet mainOf(Project project) {
        SourceSetContainer sourceSets = project.getExtensions().findByType(SourceSetContainer.class);
        return sourceSets == null ? null : sourceSets.findByName(SourceSet.MAIN_SOURCE_SET_NAME);
    }
}
