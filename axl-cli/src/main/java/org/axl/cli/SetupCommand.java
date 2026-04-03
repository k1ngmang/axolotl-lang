package org.axl.cli;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Command(name = "setup", description = {
        "Install the Axolotl libraries and build plugins that come with axl into the local Maven",
        "repository, so that Maven and Gradle projects find them."})
final class SetupCommand implements Callable<Integer> {

    @Option(names = "--repository", description = "The local Maven repository (default: ~/.m2/repository).")
    @Nullable Path repository;

    @Override
    public Integer call() throws IOException {
        Path bundled = bundledRepository();
        if (bundled == null) {
            System.err.println("axl: this installation of axl has no libraries to install; build them with"
                    + " ./gradlew publishToMavenLocal in the Axolotl repository");
            return 1;
        }
        Path target = repository != null ? repository : Path.of(System.getProperty("user.home"), ".m2", "repository");
        int count = copy(bundled, target);
        System.out.println("Installed " + count + " files of Axolotl into " + target);
        System.out.println("Maven projects can now use org.axl:axl-maven-plugin; Gradle projects the plugin");
        System.out.println("org.axl.lang, with mavenLocal() among their plugin and dependency repositories.");
        return 0;
    }

    static int copy(Path from, Path to) throws IOException {
        List<Path> files;
        try (Stream<Path> walk = Files.walk(from)) {
            files = walk.filter(Files::isRegularFile).toList();
        }
        for (Path file : files) {
            Path target = to.resolve(from.relativize(file).toString());
            Files.createDirectories(target.getParent());
            Files.copy(file, target, StandardCopyOption.REPLACE_EXISTING);
        }
        return files.size();
    }

    /** `repository` next to the jars (native installers) or next to their `lib` directory (the zip). */
    static @Nullable Path bundledRepository() {
        Path jarDir = jarDirectory();
        if (jarDir == null) {
            return null;
        }
        for (Path candidate : new Path[] {jarDir.resolve("repository"), jarDir.resolveSibling("repository")}) {
            if (Files.isDirectory(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private static @Nullable Path jarDirectory() {
        try {
            Path location = Path.of(SetupCommand.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            return Files.isRegularFile(location) ? location.getParent() : null;
        } catch (URISyntaxException | SecurityException e) {
            return null;
        }
    }
}
