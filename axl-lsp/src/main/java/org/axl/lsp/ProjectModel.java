package org.axl.lsp;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.FileVisitResult;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

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

    private static final Set<String> OUTPUTS = Set.of("build", "target", "out");

    private final List<Module> modules;
    private final List<String> problems;

    private ProjectModel(List<Module> modules, List<String> problems) {
        this.modules = modules;
        this.problems = problems;
    }

    /** A description that cannot be read is skipped, so that one stray file does not stop the others. */
    static ProjectModel discover(List<Path> workspaceRoots) {
        List<Module> modules = new ArrayList<>();
        List<String> problems = new ArrayList<>();
        for (Path root : workspaceRoots) {
            List<Path> found = candidates(root);
            for (Path path : found) {
                if (path.getFileName().toString().equals("axl.json")) {
                    try {
                        modules.add(fromConfig(path));
                    } catch (IOException | JsonParseException | InvalidPathException e) {
                        problems.add("Ignored " + path + ": " + e.getMessage());
                    }
                }
            }
            List<Path> guessed = found.stream()
                    .filter(p -> Files.isDirectory(p) && modules.stream().noneMatch(m -> m.axlRootOf(p) != null)).toList();
            for (Path path : guessed) {
                modules.add(guessedModule(path, guessed));
            }
        }
        return new ProjectModel(modules, problems);
    }

    /**
     * The descriptions and `src/NAME/axl` directories under a root. Build outputs, dependencies and
     * hidden directories are skipped: they hold copies of sources, such as Maven's integration tests,
     * and other worktrees. Only the descriptions the build plugins write are read from build outputs.
     */
    private static List<Path> candidates(Path root) {
        List<Path> found = new ArrayList<>();
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attributes) {
                    String name = dir.equals(root) ? "" : dir.getFileName().toString();
                    if (OUTPUTS.contains(name) && Files.isRegularFile(dir.resolve("axl/axl.json"))) {
                        found.add(dir.resolve("axl/axl.json"));
                    }
                    if (name.startsWith(".") || OUTPUTS.contains(name) || name.equals("node_modules")) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    if (isSourceSet(dir)) {
                        found.add(dir);
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
                    if (file.getFileName().toString().equals("axl.json")) {
                        found.add(file);
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException e) {
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        found.sort(null);
        return found;
    }

    /** `src/NAME/axl` with `src/NAME/java`; other source sets see `src/main` too, as tests do in Gradle and Maven. */
    private static Module guessedModule(Path axl, List<Path> guessed) {
        List<Path> axlRoots = new ArrayList<>(List.of(axl));
        List<Path> javaRoots = new ArrayList<>(List.of(axl.resolveSibling("java")));
        Path main = axl.getParent().resolveSibling("main").resolve("axl");
        if (!main.equals(axl) && guessed.contains(main)) {
            axlRoots.add(main);
            javaRoots.add(main.resolveSibling("java"));
        }
        javaRoots.removeIf(java -> !Files.isDirectory(java));
        return new Module(axl.getParent().getParent().getParent(), axlRoots, javaRoots, List.of(), false);
    }

    /** A `src/NAME/axl` directory. */
    private static boolean isSourceSet(Path dir) {
        Path src = dir.getParent() == null ? null : dir.getParent().getParent();
        return dir.getFileName() != null && dir.getFileName().toString().equals("axl")
                && src != null && src.getFileName() != null && src.getFileName().toString().equals("src");
    }

    private static Module fromConfig(Path file) throws IOException {
        Config config = new Gson().fromJson(Files.readString(file, StandardCharsets.UTF_8), Config.class);
        if (config == null) {
            throw new JsonParseException("no JSON object");
        }
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
        if (paths == null) {
            return List.of();
        }
        if (paths.stream().anyMatch(p -> p == null)) {
            throw new JsonParseException("a path is null");
        }
        return paths.stream().map(p -> root.resolve(p).normalize()).toList();
    }

    /**
     * The module of an .axl file, the one whose first source root holds it before one that only sees
     * it; a file outside every module gets a module of its own source root.
     */
    Module moduleOf(Path file, String packageName) {
        for (Module module : modules) {
            if (!module.axlRoots().isEmpty() && file.startsWith(module.axlRoots().getFirst())) {
                return module;
            }
        }
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

    /** Why module descriptions were skipped. */
    List<String> problems() {
        return problems;
    }
}
