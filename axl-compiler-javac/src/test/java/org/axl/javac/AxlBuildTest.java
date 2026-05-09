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
                "7:4 the `return` at the end of this function is unnecessary",
                "8:15 `String` and `Int` are never equal, so this `equals` is always false",
                "16:12 `forgotten` is never used"), warnings);
    }

    @Test
    void warnsAboutCodeWithoutEffect(@TempDir Path tmp) throws Exception {
        List<String> warnings = warnings(tmp, """
                package app

                fn size(s: String, t: String?) -> Int {
                    var n = s?.length() ?: 0
                    var m = 0
                    m += 1
                    val u: String = "x"
                    println(u!!)
                    println(t?.length())
                    println(u.equals(t))
                    return n + m
                }

                fn main() {
                    println(size("a", null))
                }
                """);
        assertEquals(List.of(
                "4:9 `n` is never assigned again; declare it with `val`",
                "4:13 `s` has the type `String`, which is never null; write `.` instead of `?.`",
                "8:13 `u` has the type `String`, which is never null; remove `!!`"), warnings);
    }

    @Test
    void warnsAboutNullChecksOfPropertiesAndValuesThatAreNeverNull(@TempDir Path tmp) throws Exception {
        List<String> warnings = warnings(tmp, """
                package app

                val top: String = "t"

                class Box(val name: String) {
                    fn size() -> Int = name?.length() ?: 0
                }

                fn f(s: String) -> String = s ?: "x"

                fn main() {
                    println(top!!)
                    println(f("a") + Box("b").size())
                }
                """);
        assertEquals(List.of(
                "6:24 `name` has the type `String`, which is never null; write `.` instead of `?.`",
                "9:29 `s` has the type `String`, which is never null; remove `?:` and its fallback",
                "12:13 `top` has the type `String`, which is never null; remove `!!`"), warnings);
    }

    @Test
    void warnsAboutAMainWithAResultType(@TempDir Path tmp) throws Exception {
        List<String> warnings = warnings(tmp, """
                package app

                fn main() -> Int = 1
                """);
        assertEquals(List.of("3:4 a `main` with a result type does not start the program; remove `-> Int`"), warnings);
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
    void wordsErrorProneWarningsWithoutJavaTerms(@TempDir Path tmp) throws Exception {
        List<String> warnings = warnings(tmp, """
                package app

                class Empty

                class Named(val name: String) : Runnable {
                    fn run() { println(name) }
                    fn toString() -> String = name
                }

                fn main() {
                    val s = "a"
                    if (s == "a") {
                        println(Empty())
                    }
                }
                """);
        assertEquals(List.of(
                "12:11 `==` tests whether both sides are the same object, not whether they are equal; compare them with "
                        + "`a.equals(b)`",
                "13:17 `Empty` has no `toString`, so it is shown as `Empty@4488aabb`; give it `fn toString() -> String`"),
                warnings);
    }

    @Test
    void wordsCalculationAndCollectionWarningsWithoutJavaAdvice(@TempDir Path tmp) throws Exception {
        List<String> warnings = warnings(tmp, """
                package app

                import java.util.ArrayList
                import java.util.LinkedList
                import java.util.Random

                enum Color { RED, GREEN, BLUE }

                fn half(a: Int) -> Double = a / 2
                fn product(a: Int, b: Int) -> Long = a * b
                fn digit(r: Random) -> Int = Math.abs(r.nextInt()) % 10

                fn show(c: Color) {
                    when (c) {
                        Color.RED -> println("r")
                    }
                }

                fn main() {
                    var s: String? = null
                    s = "x"
                    println(s)
                    val xs = ArrayList<String>()
                    xs.add("a")
                    println(LinkedList<String>())
                    println(StringBuffer())
                    println(StringBuilder().append("x").toString())
                }
                """);
        assertEquals(List.of(
                "9:29 this division of whole numbers drops the fraction before the result becomes a `Double`; make one "
                        + "side a `Double`, as in `a / 2.0`",
                "10:42 this `Int` calculation may overflow before its result becomes a `Long`; start it with a `Long`, "
                        + "as in `1L * a * b`",
                "11:35 `Math.abs` of the smallest `Int` is still negative; for a remainder that is never negative, use "
                        + "`Math.floorMod(x, n)`",
                "14:11 this `when` has no branch for `GREEN` and `BLUE`; add the missing branches or an `else ->` branch",
                "20:9 the first value of `s` is replaced before it is read; declare `s` where its value is known",
                "23:14 `xs` is filled but never read; use its values or remove it",
                "25:13 `ArrayList` or `ArrayDeque` is almost always faster than `LinkedList`; use one of them",
                "26:13 `StringBuffer` locks on every call, which is rarely needed; use `StringBuilder`",
                "27:41 join strings with `+` instead of a `StringBuilder`; it reads better and is as fast"), warnings);
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

    @Test
    void reportsAJoinedStringTooLongForJava(@TempDir Path tmp) throws Exception {
        Path src = tmp.resolve("src");
        Files.createDirectories(src.resolve("app"));
        String half = "\"" + "a".repeat(40000) + "\"";
        Files.writeString(src.resolve("app/Main.axl"),
                "package app\n\nfn main() {\n    val s = " + half + " + " + half + "\n    println(s)\n}\n");
        AxlBuild.Result result = new AxlBuild(src, tmp.resolve("generated"), tmp.resolve("classes")).nullChecks(false).run();
        List<String> errors = result.diagnostics().stream().filter(AxlDiagnostic::isError)
                .map(d -> d.line() + ":" + d.column() + " " + d.message().substring(0, 32)).toList();
        assertEquals(List.of("4:13 this string is too long for Java"), errors);
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
