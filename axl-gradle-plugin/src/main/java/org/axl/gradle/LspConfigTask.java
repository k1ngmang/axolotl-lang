package org.axl.gradle;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;
import org.gradle.api.DefaultTask;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.Classpath;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;

/** Writes `build/axl/axl.json`, from which the language server takes the source roots and classpath. */
public abstract class LspConfigTask extends DefaultTask {

    @InputFiles
    @PathSensitive(PathSensitivity.ABSOLUTE)
    public abstract ConfigurableFileCollection getSourceRoots();

    @InputFiles
    @PathSensitive(PathSensitivity.ABSOLUTE)
    public abstract ConfigurableFileCollection getJavaSourceRoots();

    @Classpath
    public abstract ConfigurableFileCollection getClasspath();

    @Input
    public abstract Property<Boolean> getStrictNull();

    @OutputFile
    public abstract RegularFileProperty getOutputFile();

    @TaskAction
    public void write() throws IOException {
        String json = "{\n"
                + "  \"sourceRoots\": " + array(getSourceRoots()) + ",\n"
                + "  \"javaSourceRoots\": " + array(getJavaSourceRoots()) + ",\n"
                + "  \"classpath\": " + array(getClasspath()) + ",\n"
                + "  \"strictNull\": " + getStrictNull().get() + "\n"
                + "}\n";
        File output = getOutputFile().get().getAsFile();
        Files.createDirectories(output.getParentFile().toPath());
        Files.writeString(output.toPath(), json, StandardCharsets.UTF_8);
    }

    private static String array(Iterable<File> files) {
        return StreamSupport.stream(files.spliterator(), false)
                .map(f -> "\"" + f.getAbsolutePath().replace("\\", "\\\\").replace("\"", "\\\"") + "\"")
                .collect(Collectors.joining(", ", "[", "]"));
    }
}
