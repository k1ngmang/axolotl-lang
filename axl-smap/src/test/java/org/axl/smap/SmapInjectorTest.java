package org.axl.smap;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.InvocationTargetException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.spi.ToolProvider;
import java.util.stream.Collectors;
import org.axl.compiler.LineMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

class SmapInjectorTest {

    @Test
    void javapShowsAxlSourceFileLinesAndSourceDebugExtension(@TempDir Path tmp) throws Exception {
        Fixture fixture = new Fixture(tmp);
        String before = javap(fixture.classes.resolve("app/Main.class"));
        assertTrue(before.contains("SourceFile: \"Main.java\""), before);

        assertEquals(2, fixture.inject());
        String after = javap(fixture.classes.resolve("app/Main.class"));

        assertTrue(after.contains("SourceFile: \"Main.axl\""), after);
        assertTrue(after.contains("SourceDebugExtension:"), after);
        assertTrue(after.contains("*S Axolotl"), after);
        int axlLines = Fixture.MAIN.split("\n").length;
        List<Integer> lines = lineNumbers(after);
        assertTrue(lines.containsAll(List.of(3, 15)), lines.toString());
        assertTrue(lines.stream().allMatch(line -> line <= axlLines), lines + " in " + axlLines + " lines");
        assertTrue(lineNumbers(before).stream().anyMatch(line -> line > axlLines), before);
    }

    @Test
    void stackTracesOfPlainJavaNameTheAxlFileAndLine(@TempDir Path tmp) throws Exception {
        Fixture fixture = new Fixture(tmp);
        fixture.inject();
        try (var loader = new URLClassLoader(new URL[] {fixture.classes.toUri().toURL()}, getClass().getClassLoader())) {
            Throwable thrown = assertThrows(InvocationTargetException.class,
                    () -> loader.loadClass("app.Main").getMethod("main", String[].class).invoke(null, (Object) new String[0])).getCause();
            assertEquals("java.lang.NumberFormatException: For input string: \"x\"", thrown.toString());
            String trace = Arrays.stream(thrown.getStackTrace()).filter(e -> e.getClassName().startsWith("app."))
                    .map(StackTraceElement::toString).collect(Collectors.joining("\n"));
            assertEquals("app.Main.parse(Main.axl:3)\napp.Main.main(Main.axl:15)", trace);
        }
    }

    @Test
    void unmappedGeneratedLinesTakeTheLineAboveAndNoLineIsInvented() throws Exception {
        LineMap lineMap = new LineMap("app/Main.axl", new int[] {0, 0, 3, 0, 0, 7, 7, 0});
        byte[] original = classWithLines("app/Main", "Main.java", 1, 3, 4, 5, 6, 7, 8);
        String smap = Smap.rewritten(lineMap).format();

        byte[] updated = SmapInjector.withAxlLines(original, lineMap, smap);

        // Lines 1 has no mapped line above it and loses its entry; 4 and 5 repeat line 3, 7 and 8 repeat line 7.
        assertEquals(List.of(3, 7), lineNumbers(javap(updated)));
    }

    @Test
    void injectingTwiceChangesNothing(@TempDir Path tmp) throws Exception {
        Fixture fixture = new Fixture(tmp);
        assertEquals(2, fixture.inject());
        byte[] once = Files.readAllBytes(fixture.classes.resolve("app/Main.class"));
        assertEquals(0, fixture.inject());
        assertArrayEquals(once, Files.readAllBytes(fixture.classes.resolve("app/Main.class")));
    }

    @Test
    void nestedClassesGetTheSmapOfTheirFile(@TempDir Path tmp) throws Exception {
        Fixture fixture = new Fixture(tmp);
        fixture.inject();
        String nested = javap(fixture.classes.resolve("app/Main$Joiner.class"));

        assertTrue(nested.contains("*S Axolotl"), nested);
        assertTrue(nested.contains("Main.axl"), nested);
    }

    @Test
    void leavesClassesOfOtherSourceFilesAlone(@TempDir Path tmp) throws Exception {
        Fixture fixture = new Fixture(tmp);
        Path stranger = fixture.classes.resolve("app/Main$Stranger.class");
        Files.write(stranger, emptyClass("app/Main$Stranger", "Stranger.java"));
        fixture.inject();

        try (InputStream in = Files.newInputStream(stranger)) {
            assertNull(SmapInjector.read(in));
        }
    }

    private static byte[] emptyClass(String internalName, String sourceFile) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, internalName, null, "java/lang/Object", null);
        writer.visitSource(sourceFile, null);
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] classWithLines(String internalName, String sourceFile, int... lines) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, internalName, null, "java/lang/Object", null);
        writer.visitSource(sourceFile, null);
        MethodVisitor method = writer.visitMethod(Opcodes.ACC_STATIC, "m", "()V", null, null);
        method.visitCode();
        for (int line : lines) {
            Label label = new Label();
            method.visitLabel(label);
            method.visitLineNumber(line, label);
            method.visitInsn(Opcodes.NOP);
        }
        method.visitInsn(Opcodes.RETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static String javap(byte[] classBytes) throws Exception {
        Path file = Files.createTempFile("axl-smap", ".class");
        try {
            Files.write(file, classBytes);
            return javap(file);
        } finally {
            Files.delete(file);
        }
    }

    private static String javap(Path classFile) {
        var out = new StringWriter();
        ToolProvider.findFirst("javap").orElseThrow()
                .run(new PrintWriter(out), new PrintWriter(out), "-v", "-l", classFile.toString());
        return out.toString();
    }

    /** The line numbers of a javap listing, in order. */
    private static List<Integer> lineNumbers(String javap) {
        List<Integer> lines = new ArrayList<>();
        for (String line : javap.lines().toList()) {
            String text = line.strip();
            if (text.startsWith("line ")) {
                lines.add(Integer.parseInt(text.substring(5, text.indexOf(':'))));
            }
        }
        return lines;
    }
}
