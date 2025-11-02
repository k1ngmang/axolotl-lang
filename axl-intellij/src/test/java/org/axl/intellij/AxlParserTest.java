package org.axl.intellij;

import com.intellij.psi.PsiErrorElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import java.util.List;

/** The PSI parser accepts what the compiler's grammar accepts, and nothing else. */
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
                "fn f() = { a, b -> a }\n", "fn f() {\n    a.b = 1\n    c = 2\n}\n")) {
            PsiFile file = myFixture.configureByText("Ok.axl", "package app\n\n" + body);
            assertNull(body, PsiTreeUtil.findChildOfType(file, PsiErrorElement.class));
        }
    }

    private void assertErrors(String body) {
        PsiFile file = myFixture.configureByText("Bad.axl", "package app\n\n" + body);
        assertNotNull(body, PsiTreeUtil.findChildOfType(file, PsiErrorElement.class));
    }
}
