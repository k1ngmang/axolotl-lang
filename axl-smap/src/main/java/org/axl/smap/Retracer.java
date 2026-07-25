package org.axl.smap;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;
import org.axl.compiler.LineMap;

/**
 * Rewrites stack frames of generated classes from .java lines to .axl lines. Frames that already
 * name an .axl file are never touched, and neither are classes whose line numbers are .axl lines.
 */
public final class Retracer {

    /** Finds the SMAP of a top-level class by its binary name, or returns null. */
    @FunctionalInterface
    public interface Lookup {
        Smap find(String className) throws IOException;
    }

    // `at app.Main.lambda$main$0(Main.java:12)`, optionally with a module or loader prefix; names may be non-ASCII.
    private static final Pattern FRAME = Pattern.compile("(\\bat\\s+(?:[\\w.$]*(?:@[^/]*)?/)*)([\\w.$]+)\\.([\\w$<>]+)\\(([\\w$-]+\\.java):(\\d+)\\)",
            Pattern.UNICODE_CHARACTER_CLASS);

    private final List<Lookup> lookups;
    private final Map<String, Optional<Smap>> cache = new HashMap<>();

    public Retracer(List<Lookup> lookups) {
        this.lookups = List.copyOf(lookups);
    }

    /** Looks in directories of generated sources (`*.axlmap`) and in class directories or jars (SMAP). */
    public static Retracer of(List<Path> mapDirs, List<Path> classpath) {
        List<Lookup> lookups = new ArrayList<>();
        for (Path dir : mapDirs) {
            lookups.add(className -> fromLineMap(dir, className));
        }
        for (Path entry : classpath) {
            lookups.add(className -> fromClassFile(entry, className));
        }
        return new Retracer(lookups);
    }

    /** Reads SMAPs of classes visible to a class loader. */
    public static Retracer of(ClassLoader loader) {
        return new Retracer(List.of(className -> {
            try (InputStream in = loader.getResourceAsStream(className.replace('.', '/') + ".class")) {
                return in == null ? null : parse(SmapInjector.read(in));
            }
        }));
    }

    public String retrace(String text) {
        Matcher m = FRAME.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            Smap smap = smapFor(m.group(2));
            int axlLine = smap == null ? 0 : smap.axlLine(Integer.parseInt(m.group(5)));
            String replacement = axlLine == 0 ? m.group()
                    : m.group(1) + m.group(2) + "." + m.group(3) + "(" + smap.axlFileName() + ":" + axlLine + ")";
            m.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    public StackTraceElement retrace(StackTraceElement element) {
        if (element.getFileName() == null || !element.getFileName().endsWith(".java") || element.getLineNumber() <= 0) {
            return element;
        }
        Smap smap = smapFor(element.getClassName());
        int axlLine = smap == null ? 0 : smap.axlLine(element.getLineNumber());
        if (axlLine == 0) {
            return element;
        }
        // The class loader name is dropped: a new element would print even the built-in `app` loader.
        return new StackTraceElement(null, element.getModuleName(), element.getModuleVersion(),
                element.getClassName(), element.getMethodName(), smap.axlFileName(), axlLine);
    }

    /** Rewrites a throwable, its causes and suppressed exceptions in place. */
    public void retrace(Throwable throwable) {
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        ArrayDeque<Throwable> queue = new ArrayDeque<>(List.of(throwable));
        while (!queue.isEmpty()) {
            Throwable t = queue.poll();
            if (!seen.add(t)) {
                continue;
            }
            StackTraceElement[] trace = t.getStackTrace();
            for (int i = 0; i < trace.length; i++) {
                trace[i] = retrace(trace[i]);
            }
            t.setStackTrace(trace);
            if (t.getCause() != null) {
                queue.add(t.getCause());
            }
            queue.addAll(List.of(t.getSuppressed()));
        }
    }

    private Smap smapFor(String className) {
        int dollar = className.indexOf('$');
        String topLevel = dollar < 0 ? className : className.substring(0, dollar);
        return cache.computeIfAbsent(topLevel, this::lookup).orElse(null);
    }

    private Optional<Smap> lookup(String className) {
        for (Lookup lookup : lookups) {
            try {
                Smap smap = lookup.find(className);
                if (smap != null) {
                    return Optional.of(smap);
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return Optional.empty();
    }

    private static Smap fromLineMap(Path dir, String className) throws IOException {
        String base = className.replace('.', '/');
        Path map = dir.resolve(base + ".axlmap");
        if (!Files.exists(map)) {
            return null;
        }
        LineMap lineMap = LineMap.fromJson(Files.readString(map, StandardCharsets.UTF_8));
        return Smap.of(lineMap, base.substring(base.lastIndexOf('/') + 1) + ".java");
    }

    private static Smap fromClassFile(Path entry, String className) throws IOException {
        String name = className.replace('.', '/') + ".class";
        if (Files.isDirectory(entry)) {
            Path file = entry.resolve(name);
            if (!Files.exists(file)) {
                return null;
            }
            try (InputStream in = Files.newInputStream(file)) {
                return parse(SmapInjector.read(in));
            }
        }
        if (!Files.isRegularFile(entry)) {
            return null;
        }
        try (ZipFile zip = new ZipFile(entry.toFile())) {
            ZipEntry zipEntry = zip.getEntry(name);
            if (zipEntry == null) {
                return null;
            }
            try (InputStream in = zip.getInputStream(zipEntry)) {
                return parse(SmapInjector.read(in));
            }
        } catch (ZipException e) {
            throw new ZipException(entry + ": " + e.getMessage());
        }
    }

    /** The SMAP of a class built without the rewrite; the lines of the others are .axl lines already. */
    private static Smap parse(String text) {
        Smap smap = text == null ? null : Smap.parse(text);
        return smap == null || smap.isRewritten() ? null : smap;
    }
}
