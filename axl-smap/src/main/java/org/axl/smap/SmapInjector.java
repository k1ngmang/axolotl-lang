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
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Makes compiled classes report .axl positions. SourceFile becomes the .axl file name and every
 * LineNumberTable entry is mapped from a generated .java line to an .axl line, so plain JVM stack
 * traces and debuggers that know nothing of Axolotl show `Main.axl:12`. A SourceDebugExtension with
 * the Axolotl stratum, which maps each .axl line to itself, keeps the classes usable by debuggers
 * that ask for that stratum.
 */
public final class SmapInjector {

    private SmapInjector() {}

    /**
     * Processes every class that has a line map in generatedDir, together with the classes nested
     * in it. Classes that were processed before are left alone.
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
        LineMap lineMap = LineMap.fromJson(Files.readString(mapFile, StandardCharsets.UTF_8));
        String smap = Smap.rewritten(lineMap).format();
        String javaName = simpleName(topLevel) + ".java";
        int changed = 0;
        for (Path classFile : classFilesOf(topLevel)) {
            byte[] original = Files.readAllBytes(classFile);
            if (!javaName.equals(sourceFile(original))) {
                continue;
            }
            byte[] updated = withAxlLines(original, lineMap, smap);
            if (!Arrays.equals(original, updated)) {
                Files.write(classFile, updated);
                changed++;
            }
        }
        return changed;
    }

    /**
     * The top-level class file and the class files compiled from the same Java file, whether or not
     * their SourceFile was already changed to the .axl file.
     */
    public static List<Path> classFilesOf(Path topLevel) throws IOException {
        String javaName = simpleName(topLevel) + ".java";
        String axlName = simpleName(topLevel) + ".axl";
        String nestedPrefix = simpleName(topLevel) + "$";
        List<Path> result = new ArrayList<>();
        result.add(topLevel);
        try (Stream<Path> siblings = Files.list(topLevel.getParent())) {
            for (Path sibling : siblings.sorted().toList()) {
                String name = sibling.getFileName().toString();
                if (name.startsWith(nestedPrefix) && name.endsWith(".class")) {
                    String source = sourceFile(Files.readAllBytes(sibling));
                    if (javaName.equals(source) || axlName.equals(source)) {
                        result.add(sibling);
                    }
                }
            }
        }
        return result;
    }

    private static String simpleName(Path classFile) {
        String name = classFile.getFileName().toString();
        return name.substring(0, name.length() - ".class".length());
    }

    private static String sourceFile(byte[] classBytes) {
        String[] source = new String[1];
        new ClassReader(classBytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public void visitSource(String name, String debug) {
                source[0] = name;
            }
        }, ClassReader.SKIP_CODE | ClassReader.SKIP_FRAMES);
        return source[0];
    }

    /**
     * Sets SourceFile to the .axl file, maps the LineNumberTable to .axl lines and stores the SMAP.
     * A generated line without a mapping takes the closest mapped line above it; a line with none
     * above it loses its entry, so the code before the first mapped line reports no line at all.
     */
    public static byte[] withAxlLines(byte[] classBytes, LineMap lineMap, String smap) {
        String path = lineMap.source();
        String axlName = path.substring(path.lastIndexOf('/') + 1);
        ClassReader reader = new ClassReader(classBytes);
        ClassWriter writer = new ClassWriter(reader, 0);
        reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
            @Override
            public void visitSource(String source, String debug) {
                super.visitSource(axlName, smap);
            }

            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor, String signature,
                    String[] exceptions) {
                return new MethodVisitor(Opcodes.ASM9, super.visitMethod(access, name, descriptor, signature, exceptions)) {
                    private int previous;

                    @Override
                    public void visitLineNumber(int line, Label start) {
                        int axlLine = lineMap.nearestAxlLine(line);
                        if (axlLine != 0 && axlLine != previous) {
                            super.visitLineNumber(axlLine, start);
                        }
                        previous = axlLine == 0 ? previous : axlLine;
                    }
                };
            }
        }, 0);
        return writer.toByteArray();
    }

    /** Adds only the SMAP and keeps SourceFile and line numbers: what earlier versions did. */
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
