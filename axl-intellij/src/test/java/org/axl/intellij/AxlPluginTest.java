package org.axl.intellij;

import com.intellij.codeInsight.intention.IntentionAction;
import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiErrorElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiReference;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.testFramework.LightProjectDescriptor;
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.axl.intellij.psi.AxlFile;
import org.axl.intellij.psi.AxlFunction;
import org.axl.intellij.psi.AxlVariable;
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

    public void testCompletesPipeStages() {
        myFixture.configureByText("Main.axl", "package app\n\nfn f(xs: java.util.List<Int>) = xs |> fil<caret>\n");
        myFixture.completeBasic();
        assertTrue(myFixture.getEditor().getDocument().getText().contains("xs |> filter"));
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
        String text = "package app\n\nfn main() {\n    val s = \"😀😀\"; val y = 1; y = 2\n}\n";
        myFixture.configureByText("Main.axl", text);
        List<Integer> errors = myFixture.doHighlighting().stream()
                .filter(h -> h.getSeverity() == HighlightSeverity.ERROR).map(h -> h.getStartOffset()).toList();
        assertEquals(List.of(text.indexOf("y = 2")), errors);
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
