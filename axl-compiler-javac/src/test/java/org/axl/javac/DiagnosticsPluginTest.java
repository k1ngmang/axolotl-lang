package org.axl.javac;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

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
        Path sources = Files.createDirectories(tmp.resolve("my src/app"));
        String text = """
                package app

                fn main() {
                    val greeting = "hi"
                    println(greeting.lenght())
                }
                """;
        Files.writeString(sources.resolve("Main.axl"), text);
        GeneratedUnit unit = Translator.translate("app/Main.axl", "app/Main.axl", text).unit();
        Path generated = tmp.resolve("gen");
        Path javaFile = unit.writeTo(generated);

        var root = new DiagnosticsPlugin.Root(generated, tmp.resolve("my src"));
        JavaCompilation.Outcome outcome = JavaCompilation.compile(List.of(javaFile), RuntimeClasspath.entries(),
                tmp.resolve("classes"), List.of("-processorpath", System.getProperty("java.class.path"),
                        DiagnosticsPlugin.option(List.of(root))), false);

        assertFalse(outcome.success());
        Diagnostic<?> error = outcome.diagnostics().stream()
                .filter(d -> d.getKind() == Diagnostic.Kind.ERROR).findFirst().orElseThrow();
        assertEquals(sources.resolve("Main.axl").toUri(), ((javax.tools.FileObject) error.getSource()).toUri());
        assertEquals(5, error.getLineNumber());
        assertEquals(22, error.getColumnNumber());
        assertEquals("`greeting` of type `String` has no method `lenght()`", error.getMessage(Locale.ROOT));
    }
}
