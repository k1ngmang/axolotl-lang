package org.axl.smap;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;
import org.axl.compiler.LineMap;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;

/**
 * Adds a SourceDebugExtension with the Axolotl SMAP to compiled classes. LineNumberTable and
 * SourceFile stay as javac wrote them.
 */
public final class SmapInjector {

    private SmapInjector() {}

    /**
     * Processes every class that has a line map in generatedDir, together with the classes nested
     * in it.
     *
     * @return the number of class files that were changed
     */
    public static int inject(Path classesDir, Path generatedDir) throws IOException {
        if (!Files.isDirectory(generatedDir) || !Files.isDirectory(classesDir)) {
            return 0;
        }
        int changed = 0;
        try (Stream<Path> files = Files.walk(generatedDir)) {
            for (Path mapFile : files.filter(p -> p.toString().endsWith(".axlmap")).sorted().toList()) {
                String relative = generatedDir.relativize(mapFile).toString().replace('\\', '/');
                String base = relative.substring(0, relative.length() - ".axlmap".length());
                changed += injectFileClasses(classesDir.resolve(base + ".class"), mapFile);
            }
        }
        return changed;
    }

    private static int injectFileClasses(Path topLevel, Path mapFile) throws IOException {
        if (!Files.exists(topLevel)) {
            return 0;
        }
        String javaName = simpleName(topLevel) + ".java";
        LineMap lineMap = LineMap.fromJson(Files.readString(mapFile, StandardCharsets.UTF_8));
        String smap = Smap.of(lineMap, javaName).format();
        int changed = 0;
        for (Path classFile : classesOf(topLevel, javaName)) {
            byte[] original = Files.readAllBytes(classFile);
            byte[] updated = withSmap(original, smap);
            if (!Arrays.equals(original, updated)) {
                Files.write(classFile, updated);
                changed++;
            }
        }
        return changed;
    }

    /** The top-level class and the classes javac compiled from the same Java file. */
    private static List<Path> classesOf(Path topLevel, String javaName) throws IOException {
        String nestedPrefix = simpleName(topLevel) + "$";
        List<Path> result = new ArrayList<>();
        result.add(topLevel);
        try (Stream<Path> siblings = Files.list(topLevel.getParent())) {
            for (Path sibling : siblings.sorted().toList()) {
                String name = sibling.getFileName().toString();
                if (name.startsWith(nestedPrefix) && name.endsWith(".class")
                        && javaName.equals(sourceFile(sibling))) {
                    result.add(sibling);
                }
            }
        }
        return result;
    }

    private static String simpleName(Path classFile) {
        String name = classFile.getFileName().toString();
        return name.substring(0, name.length() - ".class".length());
    }

    private static String sourceFile(Path classFile) throws IOException {
        String[] source = new String[1];
        try (InputStream in = Files.newInputStream(classFile)) {
            new ClassReader(in).accept(new ClassVisitor(Opcodes.ASM9) {
                @Override
                public void visitSource(String name, String debug) {
                    source[0] = name;
                }
            }, ClassReader.SKIP_CODE | ClassReader.SKIP_FRAMES);
        }
        return source[0];
    }

    public static byte[] withSmap(byte[] classBytes, String smap) {
        ClassReader reader = new ClassReader(classBytes);
        ClassWriter writer = new ClassWriter(reader, 0);
        reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
            @Override
            public void visitSource(String source, String debug) {
                super.visitSource(source, smap);
            }
        }, 0);
        return writer.toByteArray();
    }

    /** The SMAP stored in a class file, or null. */
    public static String read(InputStream classBytes) throws IOException {
        String[] debug = new String[1];
        new ClassReader(classBytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public void visitSource(String source, String extension) {
                debug[0] = extension;
            }
        }, ClassReader.SKIP_CODE | ClassReader.SKIP_FRAMES);
        return debug[0];
    }
}
