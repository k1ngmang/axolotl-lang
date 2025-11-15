package org.axl.gradle;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import net.ltgt.gradle.errorprone.CheckSeverity;
import net.ltgt.gradle.errorprone.ErrorProneOptions;
import net.ltgt.gradle.errorprone.ErrorPronePlugin;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.file.SourceDirectorySet;
import org.gradle.api.plugins.ExtensionAware;
import org.gradle.api.plugins.JavaPlugin;
import org.gradle.api.tasks.SourceSet;
import org.gradle.api.tasks.SourceSetContainer;
import org.gradle.api.tasks.TaskProvider;
import org.gradle.api.tasks.compile.JavaCompile;
import org.gradle.api.tasks.testing.Test;

/** Plugin `org.axl.lang`: .axl sources next to Java in every source set. */
public class AxlPlugin implements Plugin<Project> {

    /** Error Prone runs inside the forked javac and needs the compiler internals; so does AxlDiagnostics. */
    static final List<String> COMPILER_JVM_ARGS = List.of("api", "code", "comp", "file", "main", "model", "parser",
                    "processing", "tree", "util").stream()
            .flatMap(p -> java.util.stream.Stream.of("--add-exports=jdk.compiler/com.sun.tools.javac." + p + "=ALL-UNNAMED",
                    "--add-opens=jdk.compiler/com.sun.tools.javac." + p + "=ALL-UNNAMED"))
            .toList();

    @Override
    public void apply(Project project) {
        project.getPluginManager().apply(JavaPlugin.class);
        project.getPluginManager().apply(ErrorPronePlugin.class);

        AxlExtension axl = project.getExtensions().create("axl", AxlExtension.class);
        axl.getAddRuntime().convention(true);
        axl.getNullAway().convention(true);
        axl.getJavaRelease().convention(21);
        axl.getInlineElvis().convention(false);
        axl.getSmap().convention(true);
        axl.getStrictNull().convention(false);

        project.getConfigurations().named(JavaPlugin.IMPLEMENTATION_CONFIGURATION_NAME).configure(c ->
                c.withDependencies(deps -> {
                    if (axl.getAddRuntime().get()) {
                        deps.add(project.getDependencies().create("org.axl:axl-runtime:" + Versions.AXL));
                    }
                }));
        // Public .axl functions use axl.lang types, so consumers of a library need the runtime to compile.
        project.getPluginManager().withPlugin("java-library", plugin ->
                project.getConfigurations().named(JavaPlugin.API_CONFIGURATION_NAME).configure(c ->
                        c.withDependencies(deps -> {
                            if (axl.getAddRuntime().get()) {
                                deps.add(project.getDependencies().create("org.axl:axl-runtime:" + Versions.AXL));
                            }
                        })));
        project.getConfigurations().named(ErrorPronePlugin.CONFIGURATION_NAME).configure(c ->
                c.withDependencies(deps -> {
                    deps.add(project.getDependencies().create("com.google.errorprone:error_prone_core:" + Versions.ERROR_PRONE));
                    deps.add(project.getDependencies().create("com.uber.nullaway:nullaway:" + Versions.NULLAWAY));
                }));

        // Rewrites stack traces of failed tests to .axl lines, using the SMAPs.
        project.getConfigurations().named(JavaPlugin.TEST_RUNTIME_ONLY_CONFIGURATION_NAME).configure(c ->
                c.withDependencies(deps -> {
                    if (axl.getSmap().get()) {
                        deps.add(project.getDependencies().create("org.axl:axl-smap:" + Versions.AXL));
                    }
                }));

        project.getTasks().withType(Test.class).configureEach(test ->
                test.getJvmArgumentProviders().add(new RetraceTestsArgument(axl.getSmap())));

        SourceSetContainer sourceSets = project.getExtensions().getByType(SourceSetContainer.class);
        sourceSets.all(sourceSet -> configure(project, axl, sourceSet));

        project.getTasks().register("axlLspConfig", LspConfigTask.class, task -> {
            task.setDescription("Writes build/axl/axl.json for the Axolotl language server.");
            for (SourceSet sourceSet : sourceSets) {
                SourceDirectorySet axlSources = (SourceDirectorySet) sourceSet.getExtensions().getByName("axl");
                task.getSourceRoots().from(axlSources.getSourceDirectories());
                task.getJavaSourceRoots().from(sourceSet.getJava().getSrcDirs().stream()
                        .filter(dir -> !dir.toPath().startsWith(project.getLayout().getBuildDirectory().get().getAsFile().toPath()))
                        .toList());
                task.getClasspath().from(sourceSet.getCompileClasspath());
            }
            task.getStrictNull().set(axl.getStrictNull());
            task.getOutputFile().set(project.getLayout().getBuildDirectory().file("axl/axl.json"));
        });
    }

    private static void configure(Project project, AxlExtension axl, SourceSet sourceSet) {
        String name = sourceSet.getName();
        SourceDirectorySet sources = project.getObjects().sourceDirectorySet("axl", name + " Axolotl source");
        sources.srcDir("src/" + name + "/axl");
        sources.getFilter().include("**/*.axl");
        sourceSet.getExtensions().add(SourceDirectorySet.class, "axl", sources);
        sourceSet.getAllSource().source(sources);

        TaskProvider<GenerateAxlTask> generate = project.getTasks().register(
                sourceSet.getTaskName("generate", "Axl"), GenerateAxlTask.class, task -> {
                    task.setDescription("Translates the " + name + " .axl sources to Java.");
                    task.getSources().from(sources);
                    task.getInlineElvis().set(axl.getInlineElvis());
                    task.getStrictNull().set(axl.getStrictNull());
                    task.getOutputDir().set(project.getLayout().getBuildDirectory().dir("generated/axl/" + name));
                    task.getClasspath().from(sourceSet.getCompileClasspath());
                    java.io.File generatedRoot = project.getLayout().getBuildDirectory().dir("generated/axl").get().getAsFile();
                    task.getJavaSourceDirs().from(project.provider(() -> sourceSet.getJava().getSrcDirs().stream()
                            .filter(dir -> !dir.toPath().startsWith(generatedRoot.toPath()))
                            .collect(Collectors.toCollection(ArrayList::new))));
                });
        sourceSet.getJava().srcDir(generate.flatMap(GenerateAxlTask::getOutputDir));

        project.getDependencies().add(sourceSet.getAnnotationProcessorConfigurationName(),
                "org.axl:axl-compiler-javac:" + Versions.AXL);

        project.getTasks().named(sourceSet.getCompileJavaTaskName(), JavaCompile.class).configure(compile -> {
            compile.getOptions().getRelease().convention(axl.getJavaRelease());
            compile.getOptions().getCompilerArgs().add("-XDaddTypeAnnotationsToSymbol=true");
            compile.getOptions().setFork(true);
            compile.getOptions().getForkOptions().getJvmArgs().addAll(COMPILER_JVM_ARGS);

            DiagnosticsArguments diagnostics = project.getObjects().newInstance(DiagnosticsArguments.class);
            diagnostics.getGeneratedDir().set(generate.flatMap(GenerateAxlTask::getOutputDir));
            diagnostics.getSourceDirs().from(sources.getSourceDirectories());
            diagnostics.getStrictNull().set(axl.getStrictNull());
            compile.getOptions().getCompilerArgumentProviders().add(diagnostics);

            compile.getInputs().property("axlSmap", axl.getSmap());
            compile.doLast(new InjectSmap(generate.flatMap(GenerateAxlTask::getOutputDir), axl.getSmap()));

            ErrorProneOptions errorprone = ((ExtensionAware) compile.getOptions()).getExtensions()
                    .getByType(ErrorProneOptions.class);
            errorprone.getEnabled().set(axl.getNullAway());
            errorprone.check("NullAway", CheckSeverity.ERROR);
            errorprone.option("NullAway:JSpecifyMode", "true");
            errorprone.option("NullAway:OnlyNullMarked", "true");
        });
    }
}
