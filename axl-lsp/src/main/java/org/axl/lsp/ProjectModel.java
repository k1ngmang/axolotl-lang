package org.axl.lsp;

import com.google.gson.Gson;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Finds modules in the workspace. A module is described by `axl.json` in its directory, or by the
 * `build/axl/axl.json` and `target/axl/axl.json` files the Gradle and Maven plugins write:
 *
 * <pre>{"sourceRoots": ["src/main/axl"], "javaSourceRoots": ["src/main/java"], "classpath": ["lib/a.jar"], "strictNull": false}</pre>
 *
 * Relative paths are relative to the module directory. Without a description every `src/NAME/axl`
 * directory is a module of its own, next to `src/NAME/java`.
 */
final class ProjectModel {

    private static final class Config {
        List<String> sourceRoots = List.of();
        List<String> javaSourceRoots = List.of();
        List<String> classpath = List.of();
        boolean strictNull;
    }

    private final List<Module> modules;

    private ProjectModel(List<Module> modules) {
        this.modules = modules;
    }

    static ProjectModel discover(List<Path> workspaceRoots) {
        List<Module> modules = new ArrayList<>();
        for (Path root : workspaceRoots) {
            try (Stream<Path> files = Files.walk(root)) {
                List<Path> found = files.filter(ProjectModel::isCandidate).sorted().toList();
                for (Path path : found) {
                    if (path.getFileName().toString().equals("axl.json")) {
                        modules.add(fromConfig(path));
                    }
                }
                for (Path path : found) {
                    if (Files.isDirectory(path) && modules.stream().noneMatch(m -> m.axlRootOf(path) != null)) {
                        Path java = path.resolveSibling("java");
                        modules.add(new Module(path.getParent().getParent().getParent(), List.of(path),
                                Files.isDirectory(java) ? List.of(java) : List.of(), List.of(), false));
                    }
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return new ProjectModel(modules);
    }

    private static boolean isCandidate(Path path) {
        String text = path.toString().replace('\\', '/');
        if (text.contains("/node_modules/") || text.contains("/.git/") || text.contains("/.gradle/")) {
            return false;
        }
        if (path.getFileName() != null && path.getFileName().toString().equals("axl.json")) {
            return true;
        }
        return Files.isDirectory(path) && path.getFileName() != null && path.getFileName().toString().equals("axl")
                && path.getParent() != null && path.getParent().getParent() != null
                && path.getParent().getParent().getFileName() != null
                && path.getParent().getParent().getFileName().toString().equals("src");
    }

    private static Module fromConfig(Path file) throws IOException {
        Config config = new Gson().fromJson(Files.readString(file, StandardCharsets.UTF_8), Config.class);
        Path dir = file.getParent();
        // build/axl/axl.json and target/axl/axl.json describe the project two levels up.
        if (dir.getFileName().toString().equals("axl") && dir.getParent() != null
                && List.of("build", "target").contains(dir.getParent().getFileName().toString())) {
            dir = dir.getParent().getParent();
        }
        Path root = dir;
        return new Module(root, resolve(root, config.sourceRoots), resolve(root, config.javaSourceRoots),
                resolve(root, config.classpath), config.strictNull);
    }

    private static List<Path> resolve(Path root, List<String> paths) {
        return paths == null ? List.of() : paths.stream().map(p -> root.resolve(p).normalize()).toList();
    }

    /** The module of an .axl file; a file outside every module gets a module of its own source root. */
    Module moduleOf(Path file, String packageName) {
        for (Module module : modules) {
            if (module.axlRootOf(file) != null) {
                return module;
            }
        }
        Path dir = file.getParent();
        Path packagePath = packageName.isEmpty() ? null : Path.of("", packageName.split("\\."));
        Path root = dir;
        if (packagePath != null && dir.endsWith(packagePath)) {
            for (int i = 0; i < packagePath.getNameCount(); i++) {
                root = root.getParent();
            }
        }
        return new Module(root, List.of(root), List.of(), List.of(), false);
    }

    List<Module> modules() {
        return modules;
    }
}
