package org.axl.intellij;

import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.psi.PsiErrorElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import java.util.List;
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

    private int calls(String body) {
        PsiFile file = myFixture.configureByText("Calls.axl", "package app\n\nfn f(x: Int) -> Int = x\n\n" + body);
        return PsiTreeUtil.collectElements(file, e -> e.getNode().getElementType() == AxlElementTypes.CALL).length;
    }

    private void assertErrors(String body) {
        PsiFile file = myFixture.configureByText("Bad.axl", "package app\n\n" + body);
        assertNotNull(body, PsiTreeUtil.findChildOfType(file, PsiErrorElement.class));
    }
}
