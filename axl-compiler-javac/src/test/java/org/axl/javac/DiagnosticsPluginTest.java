package org.axl.javac;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import javax.tools.Diagnostic;
import org.axl.compiler.GeneratedUnit;
import org.axl.compiler.Translator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DiagnosticsPluginTest {

    @Test
    void reportsJavacErrorsAtAxlPosition(@TempDir Path tmp) throws Exception {
        Diagnostic<?> error = firstError(tmp, """
                package app

                fn main() {
                    val greeting = "hi"
                    println(greeting.lenght())
                }
                """);
        assertEquals(tmp.resolve("my src/app/Main.axl").toUri(), ((javax.tools.FileObject) error.getSource()).toUri());
        assertEquals(5, error.getLineNumber());
        assertEquals(22, error.getColumnNumber());
        assertEquals("`greeting` of type `String` has no method `lenght()`", error.getMessage(Locale.ROOT));
    }

    @Test
    void usesTheSimplerErrorJavacWouldShow(@TempDir Path tmp) throws Exception {
        Diagnostic<?> error = firstError(tmp, """
                package app

                fn main() {
                    val xs = java.util.List.of(1, 2)
                    println(xs |> filter { it + 1 })
                }
                """);
        assertEquals("the lambda returns `Int`, but `Boolean` is expected", error.getMessage(Locale.ROOT));
    }

    @Test
    void countsLinesEndedByCarriageReturns(@TempDir Path tmp) throws Exception {
        Diagnostic<?> error = firstError(tmp, "package app\r\rfn main() {\r    println(\"x\".lenght())\r}\r");
        assertEquals(4, error.getLineNumber());
        assertEquals(17, error.getColumnNumber());
    }

    @Test
    void namesTheAxlFileInNotes(@TempDir Path tmp) throws Exception {
        JavaCompilation.Outcome outcome = compile(tmp, """
                package app

                import java.util.Date

                fn year() -> Int = Date().getYear()
                """);
        assertTrue(outcome.diagnostics().stream().filter(d -> d.getKind() == Diagnostic.Kind.NOTE)
                .anyMatch(d -> d.getMessage(Locale.ROOT).contains("Main.axl uses or overrides a deprecated API")),
                outcome.diagnostics().toString());
    }

    private static Diagnostic<?> firstError(Path tmp, String text) throws Exception {
        JavaCompilation.Outcome outcome = compile(tmp, text);
        assertFalse(outcome.success());
        return outcome.diagnostics().stream().filter(d -> d.getKind() == Diagnostic.Kind.ERROR).findFirst().orElseThrow();
    }

    private static JavaCompilation.Outcome compile(Path tmp, String text) throws Exception {
        Path sources = Files.createDirectories(tmp.resolve("my src/app"));
        Files.writeString(sources.resolve("Main.axl"), text);
        GeneratedUnit unit = Translator.translate("app/Main.axl", "app/Main.axl", text).unit();
        Path generated = tmp.resolve("gen");
        Path javaFile = unit.writeTo(generated);

        var root = new DiagnosticsPlugin.Root(generated, tmp.resolve("my src"));
        return JavaCompilation.compile(List.of(javaFile), RuntimeClasspath.entries(),
                tmp.resolve("classes"), List.of("-processorpath", System.getProperty("java.class.path"),
                        DiagnosticsPlugin.option(List.of(root))), false);
    }
}
