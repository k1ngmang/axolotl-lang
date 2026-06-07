package org.axl.lsp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JavaStubsTest {

    @TempDir
    Path dir;

    private final JavaStubs stubs = new JavaStubs();

    @Test
    void blanksTheCodeOfMethodsAndInitializersInPlace() throws Exception {
        Path file = Files.writeString(dir.resolve("Tool.java"), """
                class Tool {
                    static int n = 1;
                    static { n = 2; }
                    int twice(int x) {
                        return x * 2;
                    }
                }
                """);
        String expected = "class Tool {\n    static int n = 1;\n    static {        }\n    int twice(int x) {\n"
                + " ".repeat(21) + "\n    }\n}\n";
        assertEquals(expected, text(file));
    }

    @Test
    void readsAFileAgainOnlyWhenItChanges() throws Exception {
        Path file = Files.writeString(dir.resolve("Tool.java"), "class Tool { void f() { g(); } }\n");
        CharSequence first = stubs.of(List.of(file)).getFirst().getCharContent(true);
        assertSame(first, stubs.of(List.of(file)).getFirst().getCharContent(true));

        Files.writeString(file, "class Tool { void h() { g(); } }\n");
        Files.setLastModifiedTime(file, FileTime.fromMillis(Files.getLastModifiedTime(file).toMillis() + 2000));
        assertNotSame(first, stubs.of(List.of(file)).getFirst().getCharContent(true));
        assertEquals("class Tool { void h() {      } }\n", text(file));
    }

    private String text(Path file) throws Exception {
        return stubs.of(List.of(file)).getFirst().getCharContent(true).toString();
    }
}
