package org.axl.lsp;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The Java sources of a module: the files in its Java source roots, and those next to its .axl
 * files, as `axl check` takes them. Next to .axl files only a file in the directory of its package
 * counts, since the other directories of a loose module's root may hold anything.
 */
final class JavaSources {

    private static final Pattern PACKAGE = Pattern.compile("^\\s*package\\s+([\\w.]+)\\s*;", Pattern.MULTILINE);

    private JavaSources() {}

    static List<Path> of(Module module) {
        List<Path> result = new ArrayList<>();
        for (Path root : module.javaRoots()) {
            result.addAll(javaFiles(root));
        }
        for (Path root : module.axlRoots()) {
            if (Stream.concat(module.javaRoots().stream(), module.javaSourcepath().stream()).noneMatch(root::startsWith)) {
                javaFiles(root).stream().filter(file -> inPackageDirectory(root, file) && !result.contains(file))
                        .forEach(result::add);
            }
        }
        return result;
    }

    private static List<Path> javaFiles(Path root) {
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter(p -> p.toString().endsWith(".java")).sorted().toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static boolean inPackageDirectory(Path root, Path file) {
        try {
            Matcher m = PACKAGE.matcher(Files.readString(file, StandardCharsets.UTF_8));
            Path directory = m.find() ? Path.of("", m.group(1).split("\\.")) : Path.of("");
            return directory.equals(root.relativize(file.getParent()));
        } catch (IOException e) {
            return false;
        }
    }
}
