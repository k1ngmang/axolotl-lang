package org.axl.smap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Path;
import java.util.spi.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SmapInjectorTest {

    @Test
    void javapShowsSourceDebugExtensionAndKeepsLineNumbers(@TempDir Path tmp) throws Exception {
        Fixture fixture = new Fixture(tmp);
        String before = javap(fixture.classes.resolve("app/Main.class"));

        assertEquals(1, fixture.inject());
        String after = javap(fixture.classes.resolve("app/Main.class"));

        assertTrue(after.contains("SourceDebugExtension:"), after);
        assertTrue(after.contains("*S Axolotl"), after);
        assertTrue(after.contains("SourceFile: \"Main.java\""), after);
        assertEquals(lineNumberTables(before), lineNumberTables(after));
    }

    @Test
    void injectingTwiceChangesNothing(@TempDir Path tmp) throws Exception {
        Fixture fixture = new Fixture(tmp);
        assertEquals(1, fixture.inject());
        assertEquals(0, fixture.inject());
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
