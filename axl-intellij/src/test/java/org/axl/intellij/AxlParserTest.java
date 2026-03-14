package org.axl.intellij;

import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.psi.PsiErrorElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.axl.intellij.lexer.AxlTokenTypes;
import org.axl.intellij.parser.AxlElementTypes;

/** Syntax errors in IntelliJ are the ones the compiler reports. */
public class AxlParserTest extends BasePlatformTestCase {

    public void testRejectsTrailingCommas() {
        assertErrors("fn f(a: Int,) -> Int = a\n");
        assertErrors("fn f(a: Int) -> Int = a\n\nfn g() = f(1,)\n");
        assertErrors("fn f() = { a, -> a }\n");
        assertErrors("fn f() = Box<String,>(1)\n");
    }

    public void testRejectsAssignmentsToOperatorExpressions() {
        assertErrors("fn f() {\n    var a = 1\n    a + 1 = 2\n}\n");
        assertErrors("fn f() {\n    var a = 1\n    -a = 3\n}\n");
    }

    public void testRejectsPrivateVar() {
        assertErrors("fn f() {\n    private var a = 1\n}\n");
    }

    public void testAcceptsWhatTheGrammarAccepts() {
        for (String body : List.of("fn f(a: Int, b: Int) -> Int = a + b\n", "fn f() = g(1, 2)\n",
                "fn f() = { a, b -> a }\n", "fn f(x: Boolean) = !!x\n", "fn f() {\n    a.b = 1\n    c = 2\n}\n")) {
            PsiFile file = myFixture.configureByText("Ok.axl", "package app\n\n" + body);
            assertNull(body, PsiTreeUtil.findChildOfType(file, PsiErrorElement.class));
        }
    }

    public void testShowsTheCompilersSyntaxErrorsForWhatThePsiParserAccepts() {
        for (String body : List.of("fn f() {\n    private val a = 1\n    println(a)\n}\n",
                "fn g() -> Int = 1\n\nfn f() {\n    g() = 2\n}\n", "fn f(s: String?) {\n    s?.length = 2\n}\n",
                "fn f() {\n    (println)(1)\n}\n")) {
            myFixture.configureByText("Rejected.axl", "package app\n\n" + body);
            assertTrue(body, myFixture.doHighlighting().stream().anyMatch(h -> h.getSeverity() == HighlightSeverity.ERROR));
        }
    }

    public void testEndsAStatementAtALineBreakInABlockComment() {
        assertEquals(0, calls("fn main() {\n    val a = f /*\n */ (1)\n}\n"));
        assertEquals(1, calls("fn main() {\n    val a = f /* one line */ (1)\n}\n"));
    }

    public void testReadsComparisonsWithNamesThatAreNotTypesAsComparisons() {
        PsiFile file = myFixture.configureByText("Cmp.axl", "package app\n\nfn f(v: Int) = g(LOW < v, v > (1))\n");
        assertEquals(0, PsiTreeUtil.collectElements(file, e -> e.getNode().getElementType() == AxlElementTypes.GENERIC_NEW).length);
        file = myFixture.configureByText("New.axl", "package app\n\nfn f() = Box<java.util.List<String>>(1)\n");
        assertEquals(1, PsiTreeUtil.collectElements(file, e -> e.getNode().getElementType() == AxlElementTypes.GENERIC_NEW).length);
    }

    private static final String SHAPES = """
            package app

            sealed interface Shape : Named {
                val name: String
            }

            private interface Named

            data class Circle(val name: String, val radius: Double) : Shape, Named

            private data class Empty()

            fn area(shape: Shape) -> Double = when (shape) {
                is Circle -> 3.0 * shape.radius()
                else -> 0.0
            }

            fn size(n: Int) -> String = when (n) {
                1, 2 -> "few"
                else -> "many"
            }

            fn describe(shape: Shape) -> String {
                if (shape !is Circle) {
                    return "other"
                }
                val total = when (val e = shape) {
                    is Circle -> {
                        val r = e.radius()
                        r * r
                    }
                    is Empty, is Circle -> 0.0
                }
                for (x in java.util.List.of(1, 2)) {
                    println(x is Int && shape is Circle)
                }
                return shape.name()
            }
            """;

    public void testAcceptsDataClassesInterfacesLoopsTypeTestsAndWhen() {
        PsiFile file = myFixture.configureByText("Shapes.axl", SHAPES);
        assertNull(PsiTreeUtil.findChildOfType(file, PsiErrorElement.class));
        assertEquals(1, PsiTreeUtil.collectElements(file, e -> e.getNode().getElementType() == AxlElementTypes.FOR).length);
        assertEquals(3, PsiTreeUtil.collectElements(file, e -> e.getNode().getElementType() == AxlElementTypes.IS_CHECK).length);
        assertEquals(6, PsiTreeUtil.collectElements(file, e -> e.getNode().getElementType() == AxlElementTypes.WHEN_ARM).length);
    }

    public void testParsesTheConformanceCasesOfTheNewSyntax() throws IOException {
        for (String name : List.of("for_loops/src/app/Main", "data_classes/src/app/Shapes", "data_classes/src/app/Main",
                "is_smart_cast/src/app/Main", "when_expressions/src/app/Main")) {
            String text = Files.readString(Path.of("..", "tests", "conformance", name + ".axl"));
            PsiFile file = myFixture.configureByText("Case.axl", text);
            assertNull(name, PsiTreeUtil.findChildOfType(file, PsiErrorElement.class));
        }
    }

    public void testEndsATypeTestAtALineBreak() {
        PsiFile file = myFixture.configureByText("Arms.axl", "package app\n\nfn f(s: Shape) = when (s) {\n    is A -> s\n"
                + "    is B -> s\n}\n");
        assertNull(PsiTreeUtil.findChildOfType(file, PsiErrorElement.class));
        assertEquals(0, PsiTreeUtil.collectElements(file, e -> e.getNode().getElementType() == AxlElementTypes.IS_CHECK).length);
        assertEquals(2, PsiTreeUtil.collectElements(file, e -> e.getNode().getElementType() == AxlElementTypes.WHEN_ARM).length);
    }

    public void testKeepsDataAndInAsNamesOutsideTheirPlaces() {
        PsiFile file = myFixture.configureByText("Names.axl", "package app\n\nfn f(data: Int, in: Int) = data + in\n");
        assertNull(PsiTreeUtil.findChildOfType(file, PsiErrorElement.class));
        assertEquals(0, PsiTreeUtil.collectElements(file,
                e -> e.getNode().getElementType() == AxlTokenTypes.CONTEXTUAL_KEYWORD).length);
        file = myFixture.configureByText("Words.axl", "package app\n\ndata class P(val x: Int)\n\nfn f() {\n    for (x in g()) {\n    }\n}\n");
        assertEquals(2, PsiTreeUtil.collectElements(file,
                e -> e.getNode().getElementType() == AxlTokenTypes.CONTEXTUAL_KEYWORD).length);
    }

    public void testRejectsWhatTheGrammarRejectsInNewConstructs() {
        assertErrors("data class P(x: Int)\n");
        assertErrors("data class P(val x: Int\n");
        assertErrors("interface I { fn f() {} }\n");
        assertErrors("fn f(xs: List<Int>) {\n    for (x xs) {\n    }\n}\n");
        assertErrors("fn f(xs: List<Int>) {\n    for (x in xs) println(x)\n}\n");
        assertErrors("fn f(n: Int) = when (n) {\n    foo -> 1\n}\n");
        assertErrors("fn f(n: Int) = when (n) {\n    1 2\n}\n");
        assertErrors("fn f(n: Int) {\n    n is Int = 1\n}\n");
    }

    private int calls(String body) {
        PsiFile file = myFixture.configureByText("Calls.axl", "package app\n\nfn f(x: Int) -> Int = x\n\n" + body);
        return PsiTreeUtil.collectElements(file, e -> e.getNode().getElementType() == AxlElementTypes.CALL).length;
    }

    private void assertErrors(String body) {
        PsiFile file = myFixture.configureByText("Bad.axl", "package app\n\n" + body);
        assertNotNull(body, PsiTreeUtil.findChildOfType(file, PsiErrorElement.class));
    }
}
