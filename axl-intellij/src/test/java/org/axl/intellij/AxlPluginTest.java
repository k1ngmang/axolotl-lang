package org.axl.intellij;

import com.intellij.codeInsight.intention.IntentionAction;
import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiErrorElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiReference;
import com.intellij.psi.codeStyle.CodeStyleManager;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.testFramework.LightProjectDescriptor;
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.axl.compiler.Options;
import org.axl.compiler.Translator;
import org.axl.intellij.java.PsiJavaTypes;
import com.intellij.ide.structureView.TreeBasedStructureViewBuilder;
import org.axl.intellij.highlight.AxlFoldingBuilder;
import org.axl.intellij.highlight.AxlStructureViewFactory;
import org.axl.intellij.highlight.AxlSyntaxHighlighter;
import org.axl.intellij.psi.AxlFile;
import org.axl.intellij.psi.AxlFunction;
import org.axl.intellij.psi.AxlTypeDeclaration;
import org.axl.intellij.psi.AxlVariable;
import org.axl.intellij.refactor.AxlNamesValidator;
import org.jetbrains.annotations.NotNull;

public class AxlPluginTest extends LightJavaCodeInsightFixtureTestCase {

    private static final String PARSE = """
            package util

            fn parse(s: String) -> Result<Int, String> =
                attempt Integer.parseInt(s) else e -> "not a number: " + s

            fn doubled(s: String) -> Result<Int, String> {
                val n = try parse(s)
                return Ok(n * 2)
            }
            """;

    @Override
    protected @NotNull LightProjectDescriptor getProjectDescriptor() {
        return JAVA_21;
    }

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        // The runtime's sources stand in for the axl-runtime jar.
        Path runtime = Path.of("..", "axl-runtime", "src", "main", "java", "axl", "lang");
        for (String name : List.of("Result", "Ok", "Err", "ThrowingSupplier", "Nulls")) {
            myFixture.addFileToProject("axl/lang/" + name + ".java", read(runtime.resolve(name + ".java")));
        }
        myFixture.addFileToProject("org/jspecify/annotations/NullMarked.java",
                "package org.jspecify.annotations;\npublic @interface NullMarked {}\n");
        myFixture.addFileToProject("org/jspecify/annotations/Nullable.java",
                "package org.jspecify.annotations;\n@java.lang.annotation.Target(java.lang.annotation.ElementType.TYPE_USE)\npublic @interface Nullable {}\n");
    }

    private static String read(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    public void testParsesWithoutErrors() {
        PsiFile file = myFixture.configureByText("Parse.axl", PARSE);
        assertNull(PsiTreeUtil.findChildOfType(file, PsiErrorElement.class));
        AxlFile axl = (AxlFile) file;
        assertEquals("util", axl.packageName());
        assertEquals(List.of("parse", "doubled"), axl.functions().stream().map(AxlFunction::getName).toList());
    }

    public void testParsesTryInExpressionsAndPipes() {
        PsiFile file = myFixture.configureByText("Pipes.axl", """
                package app
                import java.util.List

                fn parse(s: String) -> Result<Int, String> = attempt Integer.parseInt(s) else "bad"

                fn total(texts: List<String>) -> Result<Int, String> {
                    val numbers = texts |> filter { !it.isEmpty() }
                        |> mapResult { parse(it) }
                    val home = System.getenv("HOME")!!.trim()
                    return Ok(try parse("1") + try parse("2") + numbers.size() + home.length())
                }
                """);
        assertNull(PsiTreeUtil.findChildOfType(file, PsiErrorElement.class));
        myFixture.checkHighlighting(false, false, false);
    }

    public void testInfersTopLevelValuesFromJava() {
        myFixture.configureByText("Main.axl", "package app\n\nval n = Integer.parseInt(\"1\")\n");
        myFixture.checkHighlighting(false, false, false);
    }

    public void testInfersWildcardAndNullableTypesOfTopLevelValues() {
        myFixture.addFileToProject("app/Lookup.java", """
                package app;

                import org.jspecify.annotations.Nullable;

                public class Lookup {
                    public static @Nullable String find() {
                        return null;
                    }
                }
                """);
        PsiFile file = myFixture.addFileToProject("app/Main.axl", "package app\n\nval cls = \"a\".getClass()\nval found = Lookup.find()\n");
        myFixture.configureFromExistingVirtualFile(file.getVirtualFile());
        myFixture.checkHighlighting(false, false, false);
        AxlFile axl = (AxlFile) file;
        String java = Translator.translate(axl.sourcePath(), axl.getName(), axl.getText(),
                new Options(false).withJavaTypes(new PsiJavaTypes(axl))).unit().javaSource();
        assertTrue(java, java.contains("Class<? extends String> cls"));
        assertTrue(java, java.contains("@Nullable String found"));
    }

    public void testCompletesPipeStages() {
        myFixture.configureByText("Main.axl", "package app\n\nfn f(xs: java.util.List<Int>) = xs |> fil<caret>\n");
        myFixture.completeBasic();
        assertTrue(myFixture.getEditor().getDocument().getText().contains("xs |> filter"));
    }

    public void testReformatsWithTheCompilersFormatter() {
        myFixture.configureByText("Main.axl", "package app\nimport java.util.Map\nimport java.util.List\nfn f(a:Int)->Int{\n  return a+1\n}\n");
        WriteCommandAction.runWriteCommandAction(getProject(), () -> {
            CodeStyleManager.getInstance(getProject()).reformat(myFixture.getFile());
        });
        myFixture.checkResult("package app\n\nimport java.util.List\nimport java.util.Map\n\nfn f(a: Int) -> Int {\n    return a + 1\n}\n");
    }

    public void testCompletesTheKeywordsOfLoopsDataClassesAndWhen() {
        myFixture.configureByText("Main.axl", "package app\n\nfn f() {\n    whe<caret>\n}\n");
        myFixture.completeBasic();
        assertTrue(myFixture.getEditor().getDocument().getText().contains("    when\n"));
        myFixture.configureByText("Types.axl", "package app\n\nsea<caret>\n");
        myFixture.completeBasic();
        assertTrue(myFixture.getEditor().getDocument().getText().contains("sealed"));
    }

    public void testCompletesOnlyValAndFnInAnInterface() {
        myFixture.configureByText("Shape.axl", "package app\n\nsealed interface Shape {\n    v<caret>\n}\n");
        myFixture.completeBasic();
        assertTrue(myFixture.getEditor().getDocument().getText(), myFixture.getEditor().getDocument().getText().contains("    val"));
        myFixture.configureByText("Shape.axl", "package app\n\nsealed interface Shape {\n    f<caret>\n}\n");
        myFixture.completeBasic();
        assertTrue(myFixture.getEditor().getDocument().getText(), myFixture.getEditor().getDocument().getText().contains("    fn"));
        myFixture.configureByText("Shape.axl", "package app\n\nsealed interface Shape {\n    <caret>\n}\n");
        myFixture.completeBasic();
        java.util.List<String> keywords = myFixture.getLookupElementStrings();
        assertTrue(String.valueOf(keywords), keywords.containsAll(java.util.List.of("val", "fn"))
                && keywords.stream().noneMatch(java.util.List.of("class", "return", "var", "private")::contains));
    }

    public void testOffersToRunOnlyAPublicMain() {
        myFixture.configureByText("Main.axl", "package app\n\nfn ma<caret>in() {\n    println(1)\n}\n");
        assertEquals(1, myFixture.findGuttersAtCaret().size());
        myFixture.configureByText("Hidden.axl", "package app\n\nprivate fn ma<caret>in() {\n    println(1)\n}\n");
        assertEquals(List.of(), myFixture.findGuttersAtCaret());
        myFixture.configureByText("Runner.axl", "package app\n\nclass Runner(val n: Int) {\n    fn ma<caret>in() {\n        println(n)\n    }\n}\n");
        assertEquals(List.of(), myFixture.findGuttersAtCaret());
    }

    public void testReportsSyntaxErrors() {
        PsiFile file = myFixture.configureByText("Broken.axl", "package app\n\nfn main( {\n}\n");
        assertNotNull(PsiTreeUtil.findChildOfType(file, PsiErrorElement.class));
    }

    public void testHighlightsRuleViolations() {
        myFixture.configureByText("Main.axl", """
                package app

                fn parse(s: String) -> Result<Int, String> = attempt Integer.parseInt(s) else "bad"

                fn again(s: String) -> Result<Int, String> {
                    return <error descr="`return try e` makes no sense: `try` unwraps the value, but the function returns `Result`; write `return e`">try</error> parse(s)
                }

                fn f(x: Int) -> Int {
                    val y = 1
                    if (x > 0) {
                        val <error descr="`y` shadows the `y` declared at line 10; choose another name">y</error> = 2
                        return y
                    }
                    return y
                }
                """);
        myFixture.checkHighlighting(false, false, false);
    }

    public void testMarksErrorsAfterEmojiAtTheirColumn() {
        String text = "package app\n\nfn main() {\n    val s = \"\uD83D\uDE00\uD83D\uDE00\"; val y = 1; y = 2\n}\n";
        myFixture.configureByText("Main.axl", text);
        List<Integer> errors = myFixture.doHighlighting().stream()
                .filter(h -> h.getSeverity() == HighlightSeverity.ERROR).map(h -> h.getStartOffset()).toList();
        assertEquals(List.of(text.indexOf("y = 2")), errors);
    }

    public void testWarnsAboutAPackageThatDoesNotMatchTheDirectory() {
        PsiFile file = myFixture.addFileToProject("x/Main.axl", "package app\n\nfn main() {\n    println(1)\n}\n");
        myFixture.configureFromExistingVirtualFile(file.getVirtualFile());
        List<String> warnings = myFixture.doHighlighting().stream()
                .filter(h -> h.getSeverity() == HighlightSeverity.WARNING).map(h -> h.getDescription()).toList();
        assertEquals(warnings.toString(), 1, warnings.size());
        assertTrue(warnings.getFirst(), warnings.getFirst().contains("`x`"));
    }

    public void testQuickFixRemovesReturnTry() {
        myFixture.configureByText("Main.axl", """
                package app

                fn parse(s: String) -> Result<Int, String> = attempt Integer.parseInt(s) else "bad"

                fn again(s: String) -> Result<Int, String> {
                    return t<caret>ry parse(s)
                }
                """);
        IntentionAction fix = myFixture.findSingleIntention("Remove `try`");
        myFixture.launchAction(fix);
        assertTrue(myFixture.getEditor().getDocument().getText().contains("    return parse(s)\n"));
    }

    public void testQuickFixMovesTryAfterSafeCallIntoAVal() {
        myFixture.configureByText("Main.axl", """
                package app

                fn parse(s: String) -> Result<Int, String> = Ok(1)

                fn f(s: String?) -> Result<Int, String> {
                    if (true) {
                        val n = s?.substring(t<caret>ry parse("1"))
                    }
                    return Ok(1)
                }
                """);
        myFixture.launchAction(myFixture.findSingleIntention("Move `try` into a separate `val`"));
        String text = myFixture.getEditor().getDocument().getText();
        assertTrue(text, text.contains("""
                    if (true) {
                        val value = try parse("1")
                        val n = s?.substring(value)
                    }
                """));
    }

    public void testOffersNoMoveOfTryOutOfALambda() {
        myFixture.configureByText("Main.axl", """
                package app

                fn parse(s: String) -> Result<Int, String> = Ok(1)

                fn f(xs: java.util.List<String>) -> Result<Int, String> {
                    xs.forEach({ val n = t<caret>ry parse(it) })
                    return Ok(1)
                }
                """);
        assertEquals(List.of(), myFixture.filterAvailableIntentions("Move `try` into a separate `val`"));
    }

    public void testOffersNoMoveOfTryOutOfALoopOrElseIfCondition() {
        String text = """
                package app

                fn parse(s: String) -> Result<Int, String> = Ok(1)

                fn f(s: String?) -> Result<Int, String> {
                    while (s?.startsWith(try parse("1").toString()) == true) {
                        println(1)
                    }
                    if (s == null) {
                        println(2)
                    } else if (s?.startsWith(try parse("2").toString()) == true) {
                        println(3)
                    }
                    return Ok(1)
                }
                """;
        for (String at : List.of("try parse(\"1\")", "try parse(\"2\")")) {
            myFixture.configureByText("Main.axl", text);
            myFixture.getEditor().getCaretModel().moveToOffset(text.indexOf(at) + 1);
            assertEquals(at, List.of(), myFixture.filterAvailableIntentions("Move `try` into a separate `val`"));
        }
    }

    public void testResolvesLocalsAndImportedFunctions() {
        myFixture.addFileToProject("util/Parse.axl", PARSE);
        myFixture.configureByText("Main.axl", """
                package app
                import fn util.Parse.doubled

                fn main() {
                    val text = "21"
                    println(doubled(te<caret>xt))
                }
                """);
        PsiElement local = resolveAtCaret();
        assertTrue(local instanceof AxlVariable);
        assertEquals("text", ((AxlVariable) local).getName());

        myFixture.configureByText("Main2.axl", """
                package app
                import fn util.Parse.doubled

                fn main() {
                    println(doub<caret>led("21"))
                }
                """);
        PsiElement function = resolveAtCaret();
        assertTrue(function instanceof AxlFunction);
        assertEquals("Parse.axl", function.getContainingFile().getName());
    }

    public void testResolvesItToTheLambda() {
        myFixture.configureByText("It.axl", """
                package app
                import java.util.List

                fn f(xs: List<String>) = xs |> filter { i<caret>t.isEmpty() }
                """);
        PsiElement lambda = resolveAtCaret();
        assertTrue(lambda.getText(), lambda.getText().startsWith("{ it.isEmpty()"));
    }

    public void testJavaSeesAxolotlFunctionsWithoutBuild() {
        myFixture.addFileToProject("util/Parse.axl", PARSE);
        myFixture.configureByText("Caller.java", """
                package app;

                import axl.lang.*;

                class Caller {
                    String describe() {
                        Result<Integer, String> r = util.Parse.doub<caret>led("21");
                        return switch (r) {
                            case Ok<Integer, String>(var v) -> "ok " + v;
                            case Err<Integer, String>(var e) -> "fail " + e;
                        };
                    }
                }
                """);
        myFixture.checkHighlighting(false, false, false);
        PsiElement target = resolveAtCaret();
        assertTrue(target instanceof PsiMethod);
        assertTrue(target.getNavigationElement() instanceof AxlFunction);
    }

    public void testRenameUpdatesAxolotlImportsAndJavaCallers() {
        myFixture.addFileToProject("util/Parse.axl", PARSE);
        PsiFile caller = myFixture.addFileToProject("app/Caller.java", """
                package app;

                class Caller {
                    Object call() {
                        return util.Parse.doubled("21");
                    }
                }
                """);
        PsiFile main = myFixture.addFileToProject("app/Main.axl", """
                package app
                import fn util.Parse.doubled

                fn main() {
                    println(doubled("21"))
                }
                """);
        myFixture.configureFromTempProjectFile("util/Parse.axl");
        AxlFunction doubled = ((AxlFile) myFixture.getFile()).functions().get(1);
        myFixture.renameElement(doubled, "twice");

        assertTrue(myFixture.getFile().getText().contains("fn twice(s: String)"));
        assertTrue(main.getText().contains("import fn util.Parse.twice"));
        assertTrue(main.getText().contains("println(twice(\"21\"))"));
        assertTrue(caller.getText().contains("util.Parse.twice(\"21\")"));
    }

    public void testCompletesNamesKeywordsAndJavaMembers() {
        myFixture.configureByText("Main.axl", """
                package app

                fn greet(name: String) -> String {
                    val greeting = "hi"
                    return gr<caret>
                }
                """);
        myFixture.completeBasic();
        List<String> names = myFixture.getLookupElementStrings();
        assertNotNull(names);
        assertTrue(names.toString(), names.containsAll(List.of("greeting", "greet")));

        myFixture.configureByText("Main2.axl", """
                package app

                fn shout(name: String) -> String = name.toUp<caret>
                """);
        assertCompletes("toUpperCase");

        myFixture.configureByText("Main3.axl", """
                package app

                fn number(text: String) -> Int = Integer.parseI<caret>
                """);
        assertCompletes("parseInt");
    }

    public void testCompletesAfterPackagesAndOnValuesWithoutWrittenTypes() {
        myFixture.configureByText("Imports.axl", "package app\nimport java.util.Li<caret>\n");
        assertCompletes("List");

        myFixture.configureByText("Locals.axl", """
                package app

                fn shout() -> String {
                    val text = "hi"
                    return text.toUp<caret>
                }
                """);
        assertCompletes("toUpperCase");

        myFixture.configureByText("Values.axl", """
                package app

                val builder = StringBuilder("x")

                fn reversed() = builder.rever<caret>
                """);
        assertCompletes("reverse");
    }

    public void testAcceptsOnlyAxolotlNamesForRenaming() {
        AxlNamesValidator validator = new AxlNamesValidator();
        for (String name : List.of("x1", "gr\u00f6\u00dfe", "_count", "data", "in")) {
            assertTrue(name, validator.isIdentifier(name, getProject()));
        }
        for (String name : List.of("record", "yield", "sealed", "permits", "fn", "class", "is", "when", "interface", "for", "a\u20ac", "a$b", "1a", "a b")) {
            assertFalse(name, validator.isIdentifier(name, getProject()));
        }
    }

    public void testFindsUsagesInAxolotlAndJava() {
        myFixture.addFileToProject("util/Parse.axl", PARSE);
        myFixture.addFileToProject("app/Caller.java", """
                package app;

                class Caller {
                    Object call() {
                        return util.Parse.parse("1");
                    }
                }
                """);
        myFixture.configureFromTempProjectFile("util/Parse.axl");
        AxlFunction parse = ((AxlFile) myFixture.getFile()).functions().getFirst();
        List<String> files = myFixture.findUsages(parse).stream()
                .map(u -> u.getFile().getName()).sorted().toList();
        assertEquals(List.of("Caller.java", "Parse.axl"), files);
    }

    private static final String SELF = """
            package app

            class Counter(var count: Int) {
                fn inc() -> Int {
                    this.count += 1
                    return this.count
                }
                fn twice() -> Int {
                    this.inc()
                    return inc()
                }
            }
            """;

    public void testRenamesAndFindsMembersUsedThroughThis() {
        AxlFile file = (AxlFile) myFixture.configureByText("Counter.axl", SELF);
        AxlTypeDeclaration counter = file.typeDeclarations().getFirst();
        AxlVariable count = counter.fields().getFirst();
        assertEquals(2, myFixture.findUsages(count).size());
        assertEquals(2, myFixture.findUsages(counter.methods().getFirst()).size());

        myFixture.renameElement(count, "total");
        assertTrue(myFixture.getFile().getText(), myFixture.getFile().getText().contains("this.total += 1"));
        assertTrue(myFixture.getFile().getText().contains("return this.total"));

        myFixture.renameElement(counter, "Tally");
        assertTrue(myFixture.getFile().getText(), myFixture.getFile().getText().contains("this.inc()"));
    }

    public void testCompletesMembersAfterThis() {
        myFixture.configureByText("Counter.axl", SELF.replace("this.inc()", "this.<caret>"));
        myFixture.completeBasic();
        List<String> names = myFixture.getLookupElementStrings();
        assertNotNull(names);
        assertTrue(names.toString(), names.containsAll(List.of("count", "inc", "twice")) && !names.contains("wait0"));
    }

    public void testResolvesAndCompletesTheMethodsOfTheSuperclassAfterSuper() {
        myFixture.addFileToProject("app/Base.java", """
                package app;
                public class Base {
                    public int read() { return 1; }
                    protected int fill() { return 2; }
                    private int hidden() { return 3; }
                    public static int create() { return 4; }
                }
                """);
        String text = "package app\n\nclass Reader2 : Base() {\n    fn read() -> Int = super.re<caret>ad() + 1\n}\n";
        myFixture.configureByText("Reader2.axl", text);
        PsiElement target = myFixture.getElementAtCaret();
        assertTrue(target.toString(), target instanceof PsiMethod method && method.getContainingClass() != null
                && "app.Base".equals(method.getContainingClass().getQualifiedName()));
        myFixture.configureByText("Reader3.axl", "package app\n\nclass Reader3 : Base() {\n    fn read() -> Int = super.<caret>\n}\n");
        myFixture.completeBasic();
        List<String> names = myFixture.getLookupElementStrings();
        assertNotNull(names);
        assertTrue(names.toString(), names.containsAll(List.of("read", "fill")) && !names.contains("hidden") && !names.contains("create"));
    }

    public void testCompletesTheMembersOfJavaClassesOfTheSamePackageThatAreNotPublic() {
        myFixture.addFileToProject("app/Tool.java", """
                package app;
                public class Tool {
                    static int report(int n) { return n; }
                    protected static int prot() { return 1; }
                    private static int hidden() { return 2; }
                    public static int greet() { return 3; }
                }
                """);
        myFixture.configureByText("Main.axl", "package app\n\nfn main() {\n    println(Tool.<caret>)\n}\n");
        myFixture.completeBasic();
        List<String> names = myFixture.getLookupElementStrings();
        assertNotNull(names);
        assertTrue(names.toString(), names.containsAll(List.of("report", "prot", "greet")) && !names.contains("hidden"));
        myFixture.configureByText("Other.axl", "package other\n\nimport app.Tool\n\nfn main() {\n    println(Tool.<caret>)\n}\n");
        myFixture.completeBasic();
        assertEquals(List.of("greet"), myFixture.getLookupElementStrings());
    }

    private static final String SHAPES = """
            package app

            sealed interface Shape {
                val name: String
            }

            data class Circle(val name: String, val radius: Double) : Shape

            fn area(shape: Shape) -> Double = when (shape) {
                is Circle -> shape.radius()
                else -> 0.0
            }

            fn make() -> Shape = Circle("c", 1.0)

            fn total(xs: java.util.List<Int>) -> Int {
                var sum = 0
                for (x in xs) {
                    sum += x
                }
                val kind = when (val e = make()) {
                    is Circle -> e.name()
                    else -> "other"
                }
                return sum
            }
            """;

    public void testHasNoErrorsInAFileWithDataClassesInterfacesLoopsAndWhen() {
        myFixture.configureByText("Shapes.axl", SHAPES);
        assertEquals(List.of(), myFixture.doHighlighting().stream()
                .filter(h -> h.getSeverity() == HighlightSeverity.ERROR).map(h -> h.getDescription()).toList());
    }

    private static final String COUNTER = """
            package app

            enum Color { RED, GREEN }

            class Counter(val name: String, var step: Int) {
                var count: Int = 0

                fn next() -> Int {
                    count += step
                    return count
                }
            }

            fn sign(n: Int) -> String = if (n > 0) "positive" else "other"

            fn check(n: Int) -> Int = when (n) {
                1 -> 1
                else -> throw IllegalStateException("x")
            }

            fn main() {
                val c = Counter("c", 1)
                println(c.next())
            }
            """;

    public void testHasNoErrorsInAFileWithClassesEnumsConditionalsAndThrow() {
        myFixture.configureByText("Counter.axl", COUNTER);
        assertEquals(List.of(), myFixture.doHighlighting().stream()
                .filter(h -> h.getSeverity() == HighlightSeverity.ERROR).map(h -> h.getDescription()).toList());
    }

    public void testListsClassesAndEnumsWithTheirMembers() {
        AxlFile file = (AxlFile) myFixture.configureByText("Counter.axl", COUNTER);
        List<AxlTypeDeclaration> declarations = file.typeDeclarations();
        assertEquals(List.of("Color", "Counter"), declarations.stream().map(AxlTypeDeclaration::getName).toList());
        assertEquals(List.of("enum", "class"), declarations.stream().map(AxlTypeDeclaration::keyword).toList());
        assertEquals(List.of("RED", "GREEN"), declarations.getFirst().fields().stream().map(AxlVariable::getName).toList());
        assertEquals(List.of("name", "step", "count"), declarations.get(1).fields().stream().map(AxlVariable::getName).toList());
        assertEquals(List.of("next"), declarations.get(1).methods().stream().map(AxlFunction::getName).toList());
        assertEquals(List.of("sign", "check", "main"), file.functions().stream().map(AxlFunction::getName).toList());
    }

    public void testShowsClassMembersAndEnumConstantsInTheStructureView() throws Exception {
        myFixture.configureByText("Counter.axl", COUNTER);
        var builder = (TreeBasedStructureViewBuilder) new AxlStructureViewFactory().getStructureViewBuilder(myFixture.getFile());
        var model = builder.createStructureViewModel(myFixture.getEditor());
        try {
            var top = model.getRoot().getChildren();
            assertEquals(List.of("RED", "GREEN"),
                    java.util.Arrays.stream(top[0].getChildren()).map(e -> e.getPresentation().getPresentableText()).toList());
            assertEquals(List.of("name: String", "step: Int", "count: Int", "next() -> Int"),
                    java.util.Arrays.stream(top[1].getChildren()).map(e -> e.getPresentation().getPresentableText()).toList());
        } finally {
            model.dispose();
        }
    }

    public void testResolvesTheFieldsOfAClassInItsMethods() {
        myFixture.configureByText("Counter.axl", COUNTER.replace("count += step", "count += <caret>step"));
        assertEquals("step", ((AxlVariable) resolveAtCaret()).getName());
        myFixture.configureByText("Counter.axl", COUNTER.replace("count += step", "<caret>count += step"));
        assertEquals("count", ((AxlVariable) resolveAtCaret()).getName());
    }

    public void testListsDataClassesAndInterfacesWithTheirFields() {
        AxlFile file = (AxlFile) myFixture.configureByText("Shapes.axl", SHAPES);
        List<AxlTypeDeclaration> declarations = file.typeDeclarations();
        assertEquals(List.of("Shape", "Circle"), declarations.stream().map(AxlTypeDeclaration::getName).toList());
        assertEquals(List.of(true, false), declarations.stream().map(AxlTypeDeclaration::isInterface).toList());
        assertTrue(declarations.getFirst().isSealed());
        assertEquals(List.of("name"), declarations.getFirst().fields().stream().map(AxlVariable::getName).toList());
        assertEquals(List.of("name", "radius"), declarations.get(1).fields().stream().map(AxlVariable::getName).toList());
        assertEquals(List.of("area", "make", "total"), file.functions().stream().map(AxlFunction::getName).toList());
        assertEquals(List.of(), file.values());
    }

    public void testShowsDataClassesAndInterfacesInTheStructureView() throws Exception {
        myFixture.configureByText("Shapes.axl", SHAPES);
        var builder = (TreeBasedStructureViewBuilder) new AxlStructureViewFactory().getStructureViewBuilder(myFixture.getFile());
        var model = builder.createStructureViewModel(myFixture.getEditor());
        try {
            var top = model.getRoot().getChildren();
            assertEquals(List.of("Shape", "Circle", "area(shape: Shape) -> Double", "make() -> Shape",
                    "total(xs: java.util.List<Int>) -> Int"),
                    java.util.Arrays.stream(top).map(e -> e.getPresentation().getPresentableText()).toList());
            assertEquals(List.of("name: String", "radius: Double"),
                    java.util.Arrays.stream(top[1].getChildren()).map(e -> e.getPresentation().getPresentableText()).toList());
        } finally {
            model.dispose();
        }
    }

    public void testFoldsTheBodiesOfWhenAndInterface() {
        PsiFile file = myFixture.configureByText("Shapes.axl", SHAPES);
        var document = myFixture.getEditor().getDocument();
        List<String> folded = java.util.Arrays.stream(new AxlFoldingBuilder().buildFoldRegions(file, document, false))
                .map(d -> document.getText(d.getRange())).toList();
        assertTrue(folded.toString(), folded.stream().anyMatch(text -> text.startsWith("{\n    val name: String")));
        assertTrue(folded.toString(), folded.stream().anyMatch(text -> text.startsWith("{\n    is Circle -> shape.radius()")));
    }

    public void testResolvesDataClassesInterfacesLoopVariablesAndWhenBindings() {
        myFixture.configureByText("Shapes.axl", SHAPES.replace("sum += x", "sum += <caret>x"));
        assertEquals("x", resolveAtCaret().getText());
        assertTrue(resolveAtCaret() instanceof AxlVariable);

        myFixture.configureByText("Binding.axl", SHAPES.replace("e.name()", "<caret>e.name()"));
        assertEquals("e", ((AxlVariable) resolveAtCaret()).getName());

        myFixture.configureByText("Type.axl", SHAPES.replace("fn make() -> Shape", "fn make() -> <caret>Shape"));
        assertEquals("Shape", ((AxlTypeDeclaration) resolveAtCaret()).getName());

        myFixture.configureByText("Test.axl", SHAPES.replace("is Circle -> shape.radius()", "is <caret>Circle -> shape.radius()"));
        assertEquals("Circle", ((AxlTypeDeclaration) resolveAtCaret()).getName());

        myFixture.configureByText("New.axl", SHAPES.replace("= Circle(\"c\"", "= <caret>Circle(\"c\""));
        assertEquals("Circle", ((AxlTypeDeclaration) resolveAtCaret()).getName());
    }

    public void testDoesNotSeeALoopVariableOutsideItsLoop() {
        myFixture.configureByText("Scope.axl", SHAPES.replace("return sum", "return <caret>x"));
        assertNull(myFixture.getFile().findReferenceAt(myFixture.getCaretOffset()).resolve());
    }

    public void testRenamesDataClassesAndTheirUsages() {
        myFixture.configureByText("Shapes.axl", SHAPES);
        AxlTypeDeclaration circle = ((AxlFile) myFixture.getFile()).typeDeclarations().get(1);
        myFixture.renameElement(circle, "Disc");
        String text = myFixture.getFile().getText();
        assertTrue(text, text.contains("data class Disc(val name: String"));
        assertTrue(text, text.contains("is Disc -> shape.radius()"));
        assertTrue(text, text.contains("= Disc(\"c\", 1.0)"));
        assertEquals(2, text.split("is Disc", -1).length - 1);
    }

    public void testFindsTheUsagesOfAnInterface() {
        myFixture.configureByText("Shapes.axl", SHAPES);
        AxlTypeDeclaration shape = ((AxlFile) myFixture.getFile()).typeDeclarations().getFirst();
        assertEquals(3, myFixture.findUsages(shape).size());
    }

    public void testHighlightsDataAndInAsKeywordsWhereTheyAreOne() {
        myFixture.configureByText("Shapes.axl", SHAPES);
        String text = myFixture.getFile().getText();
        List<String> words = myFixture.doHighlighting().stream()
                .filter(h -> h.forcedTextAttributesKey == AxlSyntaxHighlighter.KEYWORD)
                .map(h -> text.substring(h.getStartOffset(), h.getEndOffset())).toList();
        assertEquals(List.of("data", "in"), words);
    }

    public void testConvertsJavaFileToAxolotl() {
        myFixture.configureByText("Strings.java", """
                package util;

                public final class Strings {
                    private Strings() {}

                    public static String shout(String text) {
                        final String upper = text.toUpperCase();
                        if (upper.isEmpty()) {
                            return "!";
                        }
                        return upper + "!";
                    }

                    public static void main(String[] args) {
                        System.out.println(sh<caret>out("hi"));
                    }
                }
                """);
        myFixture.launchAction(myFixture.findSingleIntention("Convert Java file to Axolotl"));
        PsiFile axl = getPsiManager().findFile(myFixture.getTempDirFixture().getFile("Strings.axl"));
        assertNotNull(axl);
        assertEquals("""
                package util

                fn shout(text: String) -> String {
                    val upper: String = text.toUpperCase()
                    if (upper.isEmpty()) {
                        return "!"
                    }
                    return upper + "!"
                }

                fn main() {
                    println(shout("hi"))
                }
                """, axl.getText());
        assertNull(PsiTreeUtil.findChildOfType(axl, PsiErrorElement.class));
    }

    /** A single match is inserted right away; several are offered in the lookup. */
    private void assertCompletes(String member) {
        myFixture.completeBasic();
        List<String> offered = myFixture.getLookupElementStrings();
        String text = myFixture.getEditor().getDocument().getText();
        assertTrue(offered + " / " + text, offered != null ? offered.contains(member) : text.contains("." + member));
    }

    private PsiElement resolveAtCaret() {
        PsiReference reference = myFixture.getFile().findReferenceAt(myFixture.getCaretOffset());
        assertNotNull("no reference at caret", reference);
        PsiElement target = reference.resolve();
        assertNotNull("unresolved: " + reference.getCanonicalText(), target);
        return target;
    }
}
