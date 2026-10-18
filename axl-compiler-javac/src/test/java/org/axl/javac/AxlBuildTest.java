package org.axl.javac;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.time.Duration;
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
    void namesTheTypesWhoseTypeArgumentsDifferWithoutErrorPronesReason(@TempDir Path tmp) throws Exception {
        List<String> warnings = warnings(tmp, """
                package app

                data class Box<T>(val value: T)

                fn main() {
                    println(Box<Int>(1).equals(Box<String>("a")))
                }
                """);
        assertEquals(List.of(
                "6:25 `Box<Int>` and `Box<String>` are never equal: the type arguments differ, so this `equals` is always false"),
                warnings);
    }

    @Test
    void warnsAboutAListGivenAsTheOnlyValueOfObjectValues(@TempDir Path tmp) throws Exception {
        List<String> warnings = warnings(tmp, """
                package app

                import java.text.MessageFormat
                import java.util.List
                import java.util.Objects

                fn main() {
                    val values: List<Object> = List.of("x", 2)
                    println(MessageFormat.format("{0}-{1}", values))
                    println(MessageFormat.format("{0}", List.of(1, 2)))
                    println(MessageFormat.format("{0}-{1}", values.toArray()))
                    println(MessageFormat.format("{0}-{1}", "a", values))
                    println(String.format("items: %s", values))
                    println(Objects.hash(values))
                }
                """);
        assertEquals(List.of(
                "9:45 the list `values` is one value here, since `format` takes any number of values; pass its elements with "
                        + "`values.toArray()`, or the values one by one",
                "10:46 this list is one value here, since `format` takes any number of values; add `.toArray()` to pass its "
                        + "elements, or pass the values one by one"), warnings);
    }

    @Test
    void namesTypesOfAFindingWithoutTheirPackages(@TempDir Path tmp) throws Exception {
        List<String> warnings = warnings(tmp, """
                package app

                import java.util.List
                import java.util.concurrent.CompletableFuture

                fn later(x: Int) -> CompletableFuture<Result<List<Int>, String>> = CompletableFuture.completedFuture(Ok(List.of(x)))

                fn main() {
                    val f = CompletableFuture.supplyAsync({ -> later(1) }).thenCompose({ g -> g })
                    println(f.join())
                }
                """);
        assertEquals(List.of("9:31 method returns a nested type, CompletableFuture<CompletableFuture<Result<List<Int>, String>>>"),
                warnings);
    }

    @Test
    void namesTheErrorProneCheckOncePerBuild(@TempDir Path tmp) throws Exception {
        List<String> warnings = warningsWithChecks(tmp, """
                package app

                import java.io.BufferedReader
                import java.io.InputStreamReader

                @SuppressWarnings("DefaultCharset")
                fn reader() -> BufferedReader = BufferedReader(InputStreamReader(System.in))

                fn other() -> BufferedReader = BufferedReader(InputStreamReader(System.in))

                fn third() -> BufferedReader = BufferedReader(InputStreamReader(System.in))

                fn main() {
                    println(reader())
                    println(other())
                    println(third())
                }
                """);
        String charset = "this uses the computer's default character set, which differs between computers; pass one, such as "
                + "`java.nio.charset.StandardCharsets.UTF_8`";
        assertEquals(List.of(
                "9:47 " + charset + " (Error Prone check DefaultCharset; @SuppressWarnings(\"DefaultCharset\") turns it off)",
                "11:47 " + charset), warnings);
    }

    @Test
    void doesNotReportTheMembersOfPrivateTypes(@TempDir Path tmp) throws Exception {
        // Java would write them private; in Axolotl the rest of the file reads them, so they are public.
        Path src = tmp.resolve("src");
        Files.createDirectories(src.resolve("app"));
        Files.writeString(src.resolve("app/Main.axl"), """
                package app

                import java.util.List

                class Patch {
                    fn apply(target: List<String>) -> Int {
                        val ctx = Context(target)
                        ctx.fuzz += 1
                        val step: Step = Twice()
                        return ctx.fuzz + ctx.result.size() + Pair(1, 2).sum() + Size.SMALL.weight + step.apply(3)
                    }
                }

                private class Context(val result: List<String>) {
                    var fuzz: Int = 0
                }

                private data class Pair(val a: Int, val b: Int) {
                    fn sum() -> Int = a + b
                }

                private enum Size(val weight: Int) {
                    SMALL(1), LARGE(2)
                }

                private interface Step {
                    fn apply(n: Int) -> Int
                }

                private class Twice : Step {
                    fn apply(n: Int) -> Int = n * 2
                }

                fn main() {
                    println(Patch().apply(List.of("a")))
                }
                """);
        AxlBuild.Result result = new AxlBuild(src, tmp.resolve("generated"), tmp.resolve("classes")).run();
        assertTrue(result.success(), result.diagnostics().toString());
        assertEquals(List.of(), result.diagnostics());
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
    void warnsAboutADroppedResult(@TempDir Path tmp) throws Exception {
        List<String> warnings = warnings(tmp, """
                package app

                import java.util.List

                interface Store {
                    fn put(key: String) -> Result<Int, String>
                }

                class Memory : Store {
                    fn put(key: String) -> Result<Int, String> = Ok(key.length())
                }

                class Bank(val names: List<String>) {
                    fn open(name: String) -> Result<Int, String> = if (names.contains(name)) Err(name) else Ok(1)

                    fn openAll() {
                        open("a")
                    }
                }

                fn check(n: Int) -> Result<Int, String> = if (n > 0) Ok(n) else Err("negative")

                fn log(n: Int) -> Int = n

                fn run(store: Store) -> Result<Int, String> {
                    val bank = Bank(List.of("b"))
                    bank.open("b")
                    store.put("k")
                    check(1)
                    log(2)
                    val unused = check(3)
                    try check(4)
                    attempt Integer.parseInt("5") else e -> println(e)
                    List.of(6) |> forEach { check(it) }
                    List.of(7) |> forEach { n ->
                        println(n)
                        check(n)
                    }
                    return Ok(0)
                }

                fn main() {
                    println(run(Memory()))
                }
                """);
        String dropped = "is dropped; handle the `Err` (`try`, `if (r is Err)`) or keep it with `val unused = ...`";
        assertEquals(List.of(
                "17:9 the `Result` of `open(...)` " + dropped,
                "27:5 the `Result` of `bank.open(...)` " + dropped,
                "28:5 the `Result` of `store.put(...)` " + dropped,
                "29:5 the `Result` of `check(...)` " + dropped), warnings);
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
    void warnsAboutComparingANameThatIsNeverNullWithNull(@TempDir Path tmp) throws Exception {
        List<String> warnings = warnings(tmp, """
                package app

                fn f(s: String, t: String?) -> Boolean = s == null || null != s || t == null

                fn main() {
                    println(f("a", null))
                }
                """);
        assertEquals(List.of(
                "3:42 `s` has the type `String`, which is never null; this comparison is always false",
                "3:63 `s` has the type `String`, which is never null; this comparison is always true"), warnings);
    }

    @Test
    void warnsAboutConstantComparisonsAndSameBranches(@TempDir Path tmp) throws Exception {
        List<String> warnings = warnings(tmp, """
                package app

                fn pick(c: Boolean) -> Int = if (c) 1000 else 1000

                fn main() {
                    println(!(1000 > 42))
                    println(pick(true))
                }
                """);
        assertEquals(List.of(
                "3:30 both branches give the same, so the condition makes no difference; keep one of them",
                "6:5 a comparison of constants here is always `true`; write `true` in its place"), warnings);
    }

    @Test
    void warnsAboutIntegerWrittenForInt(@TempDir Path tmp) throws Exception {
        List<String> warnings = warnings(tmp, """
                package app

                fn twice(n: Integer) -> Int = n * 2

                fn main() {
                    println(twice(2))
                }
                """);
        assertEquals(List.of("3:13 write `Int` instead of `Integer`; they are the same type, and `Int?` allows null"), warnings);
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
    void warnsAboutAMethodNamedLikeOneOfObject(@TempDir Path tmp) throws Exception {
        List<String> warnings = warnings(tmp, """
                package app

                class Loc(val offset: Int) {
                    fn equal(obj: Object?) -> Boolean = obj is Loc && obj.offset == offset

                    fn hashcode() -> Int = offset

                    fn tostring(prefix: String) -> String = prefix + offset
                }

                data class Point(val x: Int) {
                    fn toSting() -> String = "x" + x
                }

                class Both(val n: Int) {
                    fn equals(other: Object?) -> Boolean = other is Both && equal(other)

                    fn equal(other: Both) -> Boolean = other.n == n

                    fn hashCode() -> Int = n
                }
                """);
        assertEquals(List.of("4:8 `equal` takes the parameters of `equals` of `Object` but overrides nothing; did you mean `equals`?",
                "6:8 `hashcode` takes the parameters of `hashCode` of `Object` but overrides nothing; did you mean `hashCode`?",
                "12:8 `toSting` takes the parameters of `toString` of `Object` but overrides nothing; did you mean `toString`?"), warnings);
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
                    if (s.equals("a")) {
                        println(Empty())
                    }
                }
                """);
        assertEquals(List.of(
                "13:17 `Empty` has no `toString`, so it is shown as `Empty@4488aabb`; give it `fn toString() -> String`"),
                warnings);
    }

    @Test
    void wordsAStreamLeftOpenWithUse(@TempDir Path tmp) throws Exception {
        List<String> warnings = warnings(tmp, """
                package app

                import java.nio.file.Files
                import java.nio.file.Path

                fn main() {
                    val files = attempt Files.list(Path.of(".")) else e -> throw IllegalStateException(e)
                    println(files.count())
                }
                """);
        assertEquals(List.of("7:31 this stream keeps its file or directory open until it is closed; open it with `use (val files = "
                + "Files.list(Path.of(\".\"))) { ... } else e -> throw IllegalStateException(e)`"), warnings);
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
    void wordsErrorProneWarningsWithoutJavaNotation(@TempDir Path tmp) throws Exception {
        List<String> warnings = warnings(tmp, """
                package app

                import java.util.ArrayList
                import java.util.Date
                import java.util.HashMap
                import java.util.LinkedList

                fn f() -> Int = 1
                fn same() -> Boolean = f() == f()
                fn later(a: String, b: String) -> Boolean = a.compareTo(b) == 1
                fn size(m: HashMap<String, Int>) -> Int = m.size()
                fn queue() -> LinkedList<Int> = LinkedList<Int>()
                fn shout(s: String) -> String = s.toUpperCase()
                fn now() -> Date = Date()

                fn main() {
                    val xs = ArrayList<String>()
                    val ys = ArrayList<Int>()
                    ys.add(1)
                    ys.remove(0)
                    println(ys)
                }
                """);
        assertEquals(List.of(
                "9:28 both sides of this expression are the same, so its value is always `true` unless their calls give "
                        + "different values",
                "10:45 compare the result of `compareTo` with 0, as in `a.compareTo(b) > 0`; it need not be 1 or -1",
                "11:9 this type is a class, which ties callers to it; use `Map` instead",
                "12:33 `ArrayList` or `ArrayDeque` is almost always faster than `LinkedList`; use one of them",
                "14:20 `Date` is an old class that leads to bugs; use `java.time.Instant` or `LocalDate`",
                "17:9 `xs` is never read",
                "20:8 on a list of `Int`, `remove(i)` removes the element at index `i`, not the value `i`; to remove a "
                        + "value, write `remove(Integer.valueOf(i))`"), warnings);
    }

    @Test
    void doesNotWarnAboutTheVariableOfACountingLoop(@TempDir Path tmp) throws Exception {
        List<String> warnings = warnings(tmp, """
                package app

                import java.util.List

                fn count(xs: List<String>) -> Int {
                    var n = 0
                    for (x in xs) {
                        n += 1
                    }
                    val copy = n
                    return n
                }
                """);
        assertEquals(List.of("10:9 `copy` is never read"), warnings);
    }

    @Test
    void doesNotWarnAboutALoopOverTheCharactersOfAString(@TempDir Path tmp) throws Exception {
        List<String> warnings = warnings(tmp, """
                package app

                fn digits(s: String) -> Int {
                    var total = 0
                    for (c in s.toCharArray()) {
                        if (Character.isDigit(c)) {
                            total += c - '0'
                        }
                    }
                    return total
                }
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

                fn greet(a: Int, b: Int) {
                    val unread = 1
                    println("$a + ${a + b}")
                }
                """);
        String template = "Axolotl has no string templates, so `$` stays in the text; join the parts with `+`: "
                + "`\"value is \" + x`";
        assertEquals(List.of("5:13 " + template, "6:13 " + template, "12:9 `unread` is never read",
                "13:13 " + template), warnings);
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

    @Test
    void checksDeeplyNestedCallsInLinearTime(@TempDir Path tmp) throws Exception {
        String ids = "id(".repeat(30) + "1" + ")".repeat(30);
        String lists = "List.of(".repeat(30) + "1" + ")".repeat(30);
        String max = "x";
        for (int i = 0; i < 30; i++) {
            max = "Math.max(" + i + ", " + max + ")";
        }
        Path src = tmp.resolve("src");
        Files.createDirectories(src.resolve("app"));
        Files.writeString(src.resolve("app/Main.axl"), "package app\n\nimport java.util.List\n\nfn id(x: Int) -> Int = x\n\n"
                + "fn main() {\n    val x = 1\n    println(" + ids + ")\n    println(" + lists + ")\n    println(" + max + ")\n}\n");
        AxlBuild.Result result = assertTimeoutPreemptively(Duration.ofSeconds(20),
                () -> new AxlBuild(src, tmp.resolve("generated"), tmp.resolve("classes")).run());
        assertTrue(result.success());
    }

    @Test
    void checksOnlyTheTranslatedCodeUnlessAskedToCheckTheJavaToo(@TempDir Path tmp) throws Exception {
        // A space, a dot and a plus, which the pattern of excluded paths must escape.
        Path src = tmp.resolve("my src.d+1");
        Files.createDirectories(src.resolve("app"));
        Files.writeString(src.resolve("app/Main.axl"), """
                package app

                private val never = 3

                fn main() {
                    println(Legacy.describe(1))
                    println(Lookup.find("x").length())
                }
                """);
        Files.writeString(src.resolve("app/Legacy.java"), """
                package app;

                public class Legacy {
                    public static String describe(Object e) {
                        int count = 5;
                        return String.valueOf(e);
                    }
                }
                """);
        Files.writeString(src.resolve("app/Lookup.java"), """
                package app;

                import org.jspecify.annotations.NullMarked;
                import org.jspecify.annotations.Nullable;

                @NullMarked
                public class Lookup {
                    public static @Nullable String find(String key) {
                        return key.isEmpty() ? null : key;
                    }
                }
                """);
        List<String> generatedOnly = messages(new AxlBuild(src, tmp.resolve("generated"), tmp.resolve("classes")).run());
        assertEquals(List.of(
                "Main.axl:3:13 `never` is never read",
                "Main.axl:7:30 `Lookup.find(\"x\")` may be null: copy it to a `val` and check that, or use `?.`, `?:` or `!!`"),
                generatedOnly.stream().map(ErrorProneMessages::withoutSuppression).toList());

        List<String> all = messages(new AxlBuild(src, tmp.resolve("generated"), tmp.resolve("classes")).checkJava(true).run());
        assertTrue(all.containsAll(generatedOnly), all.toString());
        assertTrue(all.stream().anyMatch(m -> m.startsWith("Legacy.java:5:13 [UnusedVariable]")), all.toString());

        Files.writeString(src.resolve("app/Legacy.java"), """
                package app;

                public class Legacy {
                    public static String describe(Object e) {
                        return 1;
                    }
                }
                """);
        List<String> broken = messages(new AxlBuild(src, tmp.resolve("generated"), tmp.resolve("classes")).run());
        assertTrue(broken.stream().anyMatch(m -> m.startsWith("Legacy.java:5:16 incompatible types")), broken.toString());
    }

    private static List<String> messages(AxlBuild.Result result) {
        return result.diagnostics().stream()
                .map(d -> Path.of(d.file()).getFileName() + ":" + d.line() + ":" + d.column() + " " + d.message()).toList();
    }

    /** The warnings without the Error Prone check that the first warning of each check names. */
    private static List<String> warnings(Path tmp, String source) throws Exception {
        return warningsWithChecks(tmp, source).stream().map(ErrorProneMessages::withoutSuppression).toList();
    }

    private static List<String> warningsWithChecks(Path tmp, String source) throws Exception {
        Path src = tmp.resolve("src");
        Files.createDirectories(src.resolve("app"));
        Files.writeString(src.resolve("app/Main.axl"), source);
        AxlBuild.Result result = new AxlBuild(src, tmp.resolve("generated"), tmp.resolve("classes")).run();
        return result.diagnostics().stream().filter(d -> d.severity() == AxlDiagnostic.Severity.WARNING)
                .map(d -> d.line() + ":" + d.column() + " " + d.message()).toList();
    }
}
