package org.axl.javac;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.axl.compiler.AxlDiagnostic;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AxlBuildTest {

    @Test
    void wordsWarningsInAxolotlTerms(@TempDir Path tmp) throws Exception {
        List<String> warnings = warnings(tmp, """
                package app

                private val never = 3

                private fn unused(a: Int) -> Int = 1

                fn f(s: String) {
                    println(s.equals(1))
                    return
                }

                fn main() {
                    println(unused(1))
                }

                private fn forgotten() -> Int = 2
                """);
        assertEquals(List.of(
                "3:13 `never` is never read",
                "5:19 `a` is never read",
                "7:4 [ReturnAtTheEndOfVoidFunction] the `return` at the end of this function is unnecessary",
                "8:15 [EqualsIncompatibleType] Calling equals on incompatible types String and Int",
                "16:12 `forgotten` is never used"), warnings);
    }

    @Test
    void dropsAWarningAtTheSamePlaceAsAnError(@TempDir Path tmp) throws Exception {
        List<String> warnings = warnings(tmp, """
                package app

                fn same(x: Int?, y: Int?) -> Boolean = x == y
                """);
        assertEquals(List.of(), warnings);
    }

    @Test
    void warnsAboutKotlinStringTemplates(@TempDir Path tmp) throws Exception {
        List<String> warnings = warnings(tmp, """
                package app

                fn main() {
                    val name = "a"
                    println("Hello, $name" + name)
                    println("${name.length()}")
                    println("$t")
                    println("costs $5")
                }
                """);
        String template = "Axolotl has no string templates, so `$` stays in the text; join the parts with `+`: "
                + "`\"value is \" + x`";
        assertEquals(List.of("5:13 " + template, "6:13 " + template), warnings);
    }

    @Test
    void explainsCreatingAnInterfaceOrAbstractClass(@TempDir Path tmp) throws Exception {
        Path src = tmp.resolve("src");
        Files.createDirectories(src.resolve("app"));
        Files.writeString(src.resolve("app/Main.axl"), """
                package app

                import java.util.AbstractList
                import java.util.List

                interface Named {
                    val name: String
                }

                fn main() {
                    println(List<String>())
                    println(Named())
                    println(AbstractList<String>())
                }
                """);
        AxlBuild.Result result = new AxlBuild(src, tmp.resolve("generated"), tmp.resolve("classes")).nullChecks(false).run();
        List<String> errors = result.diagnostics().stream().filter(AxlDiagnostic::isError)
                .map(d -> d.line() + ":" + d.column() + " " + d.message()).toList();
        assertEquals(List.of(
                "11:13 `List` is an interface and cannot be created; create a class that implements it, such as "
                        + "`ArrayList<String>()`",
                "12:13 `Named` is an interface and cannot be created; create a class that implements it",
                "13:13 `AbstractList` is abstract and cannot be created; use a class that extends it"), errors);
    }

    private static List<String> warnings(Path tmp, String source) throws Exception {
        Path src = tmp.resolve("src");
        Files.createDirectories(src.resolve("app"));
        Files.writeString(src.resolve("app/Main.axl"), source);
        AxlBuild.Result result = new AxlBuild(src, tmp.resolve("generated"), tmp.resolve("classes")).run();
        return result.diagnostics().stream().filter(d -> d.severity() == AxlDiagnostic.Severity.WARNING)
                .map(d -> d.line() + ":" + d.column() + " " + d.message()).toList();
    }
}
