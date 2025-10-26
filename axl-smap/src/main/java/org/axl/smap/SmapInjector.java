package org.axl.smap;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
     * Processes every class that has a line map in generatedDir.
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
                Path classFile = classesDir.resolve(base + ".class");
                if (!Files.exists(classFile)) {
                    continue;
                }
                LineMap lineMap = LineMap.fromJson(Files.readString(mapFile, StandardCharsets.UTF_8));
                String javaName = base.substring(base.lastIndexOf('/') + 1) + ".java";
                byte[] original = Files.readAllBytes(classFile);
                byte[] updated = withSmap(original, Smap.of(lineMap, javaName).format());
                if (!java.util.Arrays.equals(original, updated)) {
                    Files.write(classFile, updated);
                    changed++;
                }
            }
        }
        return changed;
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
