package org.axl.javac;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.ForwardingJavaFileManager;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SharedPluginsTest {

    private static final String SOURCE = """
            import org.jspecify.annotations.NullMarked;
            import org.jspecify.annotations.Nullable;

            @NullMarked
            class A {
                String f(@Nullable String s) {
                    return s;
                }
            }
            """;

    // Windows keeps nothing: a kept loader holds its jars open, and Windows cannot replace an open file.
    private static final boolean KEEPS = File.separatorChar != '\\';

    @TempDir
    Path tmp;

    @AfterEach
    void forgetLoaders() {
        System.getProperties().remove(SharedPlugins.PROPERTY);
    }

    @Test
    void keepsThePluginsOfAProcessorPathBetweenCompilations() throws IOException {
        List<URL> path = processorPath();
        List<String> alone = compile(path, false);
        List<String> first = compile(path, true);
        ClassLoader kept = KEEPS ? onlyLoader() : null;
        List<String> second = compile(path, true);

        assertTrue(first.stream().anyMatch(e -> e.contains("[NullAway]")), first.toString());
        assertEquals(alone, first);
        assertEquals(first, second);
        if (KEEPS) {
            assertSame(kept, onlyLoader());
        } else {
            assertNull(System.getProperties().get(SharedPlugins.PROPERTY));
        }
    }

    @Test
    void loadsThePluginsAgainForAChangedJar() throws IOException {
        List<URL> path = processorPath();
        compile(path, true);
        if (!KEEPS) {
            assertNull(System.getProperties().get(SharedPlugins.PROPERTY));
            return;
        }
        ClassLoader kept = onlyLoader();
        Path jar = Path.of(path.getFirst().getPath());
        Files.setLastModifiedTime(jar, FileTime.fromMillis(Files.getLastModifiedTime(jar).toMillis() - 10_000));
        List<String> errors = compile(path, true);

        Map<?, ?> loaders = (Map<?, ?>) System.getProperties().get(SharedPlugins.PROPERTY);
        assertTrue(errors.stream().anyMatch(e -> e.contains("[NullAway]")), errors.toString());
        assertEquals(2, loaders.size());
        assertTrue(loaders.containsValue(kept));
    }

    @Test
    void keepsNothingWithoutTheOption() throws IOException {
        List<String> errors = compile(processorPath(), false);
        assertTrue(errors.stream().anyMatch(e -> e.contains("[NullAway]")), errors.toString());
        assertNull(System.getProperties().get(SharedPlugins.PROPERTY));
    }

    private static ClassLoader onlyLoader() {
        Map<?, ?> loaders = (Map<?, ?>) System.getProperties().get(SharedPlugins.PROPERTY);
        assertNotNull(loaders);
        assertEquals(1, loaders.size());
        return (ClassLoader) loaders.values().iterator().next();
    }

    /** This module as a jar, which comes first as in a build, then the jars of the test classpath, Error Prone's among them. */
    private List<URL> processorPath() throws IOException {
        Path jar = tmp.resolve("axl-compiler-javac.jar");
        List<URL> urls = new ArrayList<>(List.of(jar.toUri().toURL()));
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(jar))) {
            for (String entry : System.getProperty("java.class.path").split(File.pathSeparator)) {
                Path path = Path.of(entry);
                if (Files.isDirectory(path) && path.toString().replace('\\', '/').matches(".*/axl-compiler-javac/build/(classes/java|resources)/main")) {
                    addTree(out, path);
                } else if (entry.endsWith(".jar")) {
                    urls.add(path.toUri().toURL());
                }
            }
        }
        return urls;
    }

    private static void addTree(ZipOutputStream out, Path root) throws IOException {
        try (Stream<Path> files = Files.walk(root)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                out.putNextEntry(new ZipEntry(root.relativize(file).toString().replace('\\', '/')));
                try (OutputStream entry = new FilterOutputStream(out) {
                    @Override
                    public void close() {}
                }) {
                    Files.copy(file, entry);
                }
                out.closeEntry();
            }
        }
    }

    /** Compiles as Gradle does: with a new loader for the processor path, which sees only the JDK. */
    private List<String> compile(List<URL> path, boolean shared) throws IOException {
        Path source = tmp.resolve("A.java");
        Files.writeString(source, SOURCE);
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        var collector = new DiagnosticCollector<JavaFileObject>();
        try (StandardJavaFileManager files = compiler.getStandardFileManager(collector, Locale.ROOT, StandardCharsets.UTF_8)) {
            var manager = new ForwardingJavaFileManager<>(files) {
                @Override
                public ClassLoader getClassLoader(Location location) {
                    return location == StandardLocation.ANNOTATION_PROCESSOR_PATH
                            ? new URLClassLoader(path.toArray(URL[]::new), new JdkOnly()) : super.getClassLoader(location);
                }
            };
            List<String> options = new ArrayList<>(List.of("-d", Files.createDirectories(tmp.resolve("classes")).toString(),
                    "-classpath", System.getProperty("java.class.path"), "-proc:none",
                    "-processorpath", String.join(File.pathSeparator, path.stream().map(URL::getPath).toList()),
                    "-XDcompilePolicy=simple", "-XDaddTypeAnnotationsToSymbol=true", "--should-stop=ifError=FLOW",
                    "-Xplugin:" + DiagnosticsPlugin.NAME, JavaCompilation.ERROR_PRONE_PLUGIN));
            if (shared) {
                options.add("-XD" + SharedPlugins.OPTION);
            }
            compiler.getTask(null, manager, collector, options, null, files.getJavaFileObjects(source)).call();
        }
        return collector.getDiagnostics().stream().filter(d -> d.getKind() == Diagnostic.Kind.ERROR)
                .map(d -> d.getLineNumber() + ": " + d.getMessage(Locale.ROOT)).toList();
    }

    /** Like the parent Gradle gives the processor path: the JDK, without the classpath of the test. */
    private static final class JdkOnly extends ClassLoader {

        private static final List<String> PREFIXES = Arrays.asList("java.", "javax.", "jdk.", "sun.", "com.sun.", "org.xml.", "org.w3c.");

        JdkOnly() {
            super(ClassLoader.getSystemClassLoader());
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (PREFIXES.stream().noneMatch(name::startsWith) || name.startsWith("javax.inject.")) {
                throw new ClassNotFoundException(name);
            }
            return super.loadClass(name, resolve);
        }

        @Override
        public URL getResource(String name) {
            return null;
        }

        @Override
        public Enumeration<URL> getResources(String name) {
            return Collections.emptyEnumeration();
        }
    }
}
