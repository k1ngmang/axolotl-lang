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
        "Install the Axolotl libraries and build plugins into the local Maven repository.",
        "Maven and Gradle projects then find the ones that come with this axl."})
final class SetupCommand implements Callable<Integer> {

    @Option(names = "--repository", description = "The local Maven repository (default: -Dmaven.repo.local in MAVEN_OPTS,"
            + " the localRepository of ~/.m2/settings.xml, or ~/.m2/repository).")
    @Nullable Path repository;

    @Override
    public Integer call() throws IOException {
        if (repository != null && repository.toString().isBlank()) {
            System.err.println("axl: --repository needs a directory");
            return 2;
        }
        Path bundled = bundledRepository();
        if (bundled == null) {
            System.err.println("axl: this installation of axl has no libraries to install. In the Axolotl repository,"
                    + " ./gradlew publishToMavenLocal installs the libraries and the Gradle plugin, and"
                    + " ./mvnw install -Dinvoker.skip=true in axl-maven-plugin the Maven plugin (see docs/install.md)");
            return 1;
        }
        Path target = (repository != null ? repository : LocalRepository.find()).toAbsolutePath();
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

    /**
     * `repository` next to the directory of the jars: next to `lib` in the zip, and in jpackage's
     * app content directory in the app image (`lib` on Linux, the root on Windows, `Contents` on macOS).
     */
    static @Nullable Path bundledRepository() {
        Path jarDir = jarDirectory();
        if (jarDir == null) {
            return null;
        }
        Path repository = jarDir.resolveSibling("repository");
        return Files.isDirectory(repository) ? repository : null;
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
