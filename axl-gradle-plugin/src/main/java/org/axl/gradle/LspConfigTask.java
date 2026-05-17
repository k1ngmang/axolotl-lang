package org.axl.gradle;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;
import org.gradle.api.DefaultTask;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Classpath;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.Nested;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;
import org.gradle.work.DisableCachingByDefault;

/**
 * Writes `build/axl/axl.json`, from which the language server takes its modules: one for each
 * source set, with its source roots and classpath.
 */
@DisableCachingByDefault(because = "The file holds absolute paths of this machine")
public abstract class LspConfigTask extends DefaultTask {

    /** A module of the language server: the source roots it sees, its own first, and its classpath. */
    public abstract static class Module {

        @InputFiles
        @PathSensitive(PathSensitivity.ABSOLUTE)
        public abstract ConfigurableFileCollection getSourceRoots();

        @InputFiles
        @PathSensitive(PathSensitivity.ABSOLUTE)
        public abstract ConfigurableFileCollection getJavaSourceRoots();

        @Classpath
        public abstract ConfigurableFileCollection getClasspath();
    }

    private final List<Module> modules = new ArrayList<>();

    @Nested
    public List<Module> getModules() {
        return modules;
    }

    @Input
    public abstract Property<Boolean> getStrictNull();

    @OutputFile
    public abstract RegularFileProperty getOutputFile();

    @TaskAction
    public void write() throws IOException {
        String json = modules.stream().map(this::module).collect(Collectors.joining(",\n", "{\n  \"modules\": [\n", "\n  ]\n}\n"));
        File output = getOutputFile().get().getAsFile();
        Files.createDirectories(output.getParentFile().toPath());
        Files.writeString(output.toPath(), json, StandardCharsets.UTF_8);
    }

    private String module(Module module) {
        return "    {\n"
                + "      \"sourceRoots\": " + array(module.getSourceRoots()) + ",\n"
                + "      \"javaSourceRoots\": " + array(module.getJavaSourceRoots()) + ",\n"
                + "      \"classpath\": " + array(module.getClasspath()) + ",\n"
                + "      \"strictNull\": " + getStrictNull().get() + "\n"
                + "    }";
    }

    private static String array(Iterable<File> files) {
        return StreamSupport.stream(files.spliterator(), false)
                .map(f -> "\"" + f.getAbsolutePath().replace("\\", "\\\\").replace("\"", "\\\"") + "\"")
                .collect(Collectors.joining(", ", "[", "]"));
    }
}
