package org.axl.javac;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The classes of the jars and class directories of a classpath by simple name, for the import that an
 * unknown name needs. The classpath is read at the first question, which only an error asks.
 */
final class ClasspathTypes {

    private static final Pattern ANONYMOUS = Pattern.compile("\\.\\d|\\.\\.|\\.$");

    private final List<Path> classpath;
    private Map<String, Set<String>> bySimpleName;

    ClasspathTypes(List<Path> classpath) {
        this.classpath = List.copyOf(classpath);
    }

    /** The qualified names of the classes with this simple name, such as `grid.Grid.Pos` for `Pos`, sorted. */
    List<String> named(String name) {
        if (bySimpleName == null) {
            bySimpleName = index();
        }
        return List.copyOf(bySimpleName.getOrDefault(name, Set.of()));
    }

    private Map<String, Set<String>> index() {
        Map<String, Set<String>> names = new HashMap<>();
        for (Path entry : classpath) {
            try {
                if (Files.isDirectory(entry)) {
                    try (Stream<Path> files = Files.walk(entry)) {
                        files.filter(p -> p.toString().endsWith(".class"))
                                .forEach(p -> add(names, entry.relativize(p).toString().replace(File.separatorChar, '/')));
                    }
                } else if (entry.toString().endsWith(".jar") && Files.isRegularFile(entry)) {
                    try (JarFile jar = new JarFile(entry.toFile())) {
                        jar.stream().map(JarEntry::getName).filter(n -> n.endsWith(".class")).forEach(n -> add(names, n));
                    }
                }
            } catch (IOException | UncheckedIOException e) {
                // An entry that cannot be read has no import to suggest.
            }
        }
        return names;
    }

    /** `grid/Grid$Pos.class` as `grid.Grid.Pos`; a class of the unnamed package cannot be imported. */
    private static void add(Map<String, Set<String>> names, String classFile) {
        String path = classFile.substring(0, classFile.length() - ".class".length());
        if (!path.contains("/") || path.startsWith("META-INF/") || path.endsWith("-info")) {
            return;
        }
        String qualified = path.replace('/', '.').replace('$', '.');
        String simple = qualified.substring(qualified.lastIndexOf('.') + 1);
        // Anonymous and local classes, and the classes inside them, have a name that starts with a number.
        if (!ANONYMOUS.matcher(qualified).find()) {
            names.computeIfAbsent(simple, k -> new TreeSet<>()).add(qualified);
        }
    }
}
