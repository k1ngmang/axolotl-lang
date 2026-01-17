package org.axl.smap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.InvocationTargetException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import org.axl.compiler.LineMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RetracerTest {

    @Test
    void retracesTextUsingLineMaps(@TempDir Path tmp) throws Exception {
        Fixture fixture = new Fixture(tmp);
        String trace = """
                Exception in thread "main" java.lang.NumberFormatException: For input string: "x"
                \tat java.base/java.lang.Integer.parseInt(Integer.java:661)
                \tat app.Main.parse(Main.java:%d)
                \tat app//app.Main.main(Main.java:%d)
                """.formatted(javaLine(fixture, "return Integer.parseInt(s);"), javaLine(fixture, "parse(\"x\")"));

        String retraced = Retracer.of(List.of(fixture.generated), List.of()).retrace(trace);

        assertTrue(retraced.contains("at java.base/java.lang.Integer.parseInt(Integer.java:661)"), retraced);
        assertTrue(retraced.contains("at app.Main.parse(Main.axl:3)"), retraced);
        assertTrue(retraced.contains("at app//app.Main.main(Main.axl:15)"), retraced);
    }

    @Test
    void retracesThrowablesUsingSmapsOfLoadedClasses(@TempDir Path tmp) throws Exception {
        Fixture fixture = new Fixture(tmp);
        fixture.inject();
        try (var loader = new URLClassLoader(new URL[] {fixture.classes.toUri().toURL()}, getClass().getClassLoader())) {
            Throwable thrown = null;
            try {
                loader.loadClass("app.Main").getMethod("parse", String.class).invoke(null, "x");
            } catch (InvocationTargetException e) {
                thrown = e.getCause();
            }
            Retracer.of(loader).retrace(thrown);
            StackTraceElement frame = Arrays.stream(thrown.getStackTrace())
                    .filter(e -> e.getClassName().equals("app.Main")).findFirst().orElseThrow();
            assertEquals("Main.axl", frame.getFileName());
            assertEquals(3, frame.getLineNumber());
        }
    }

    @Test
    void leavesUnknownFramesAlone() {
        String trace = "\tat org.example.Other.run(Other.java:10)\n";
        assertEquals(trace, Retracer.of(List.of(), List.of()).retrace(trace));
    }

    @Test
    void retracesClassesWithNonAsciiNames() {
        Smap smap = Smap.of(new LineMap("app/Gr\u00f6\u00dfe.axl", new int[] {0, 0, 5}), "Gr\u00f6\u00dfe.java");
        Retracer retracer = new Retracer(List.of(className -> className.equals("app.Gr\u00f6\u00dfe") ? smap : null));
        assertEquals("\tat app.Gr\u00f6\u00dfe.m\u00e4h(Gr\u00f6\u00dfe.axl:5)\n",
                retracer.retrace("\tat app.Gr\u00f6\u00dfe.m\u00e4h(Gr\u00f6\u00dfe.java:3)\n"));
    }

    private static int javaLine(Fixture fixture, String text) throws Exception {
        List<String> lines = java.nio.file.Files.readAllLines(fixture.generated.resolve("app/Main.java"));
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).contains(text)) {
                return i + 1;
            }
        }
        throw new AssertionError(text);
    }
}
