package org.axl.smap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.spi.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;

class SmapInjectorTest {

    @Test
    void javapShowsSourceDebugExtensionAndKeepsLineNumbers(@TempDir Path tmp) throws Exception {
        Fixture fixture = new Fixture(tmp);
        String before = javap(fixture.classes.resolve("app/Main.class"));

        assertEquals(2, fixture.inject());
        String after = javap(fixture.classes.resolve("app/Main.class"));

        assertTrue(after.contains("SourceDebugExtension:"), after);
        assertTrue(after.contains("*S Axolotl"), after);
        assertTrue(after.contains("SourceFile: \"Main.java\""), after);
        assertEquals(lineNumberTables(before), lineNumberTables(after));
    }

    @Test
    void injectingTwiceChangesNothing(@TempDir Path tmp) throws Exception {
        Fixture fixture = new Fixture(tmp);
        assertEquals(2, fixture.inject());
        assertEquals(0, fixture.inject());
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

    private static String javap(Path classFile) {
        var out = new StringWriter();
        ToolProvider.findFirst("javap").orElseThrow()
                .run(new PrintWriter(out), new PrintWriter(out), "-v", "-l", classFile.toString());
        return out.toString();
    }

    private static String lineNumberTables(String javap) {
        StringBuilder sb = new StringBuilder();
        for (String line : javap.lines().toList()) {
            if (line.strip().startsWith("line ")) {
                sb.append(line.strip()).append('\n');
            }
        }
        return sb.toString();
    }
}
