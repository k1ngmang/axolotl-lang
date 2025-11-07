package org.axl.intellij;

import com.intellij.psi.PsiElement;
import com.intellij.testFramework.LightProjectDescriptor;
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase;
import java.util.List;
import org.axl.intellij.psi.AxlFile;
import org.axl.intellij.psi.AxlFunction;
import org.jetbrains.annotations.NotNull;

/** Calls of overloaded functions belong to the overload their arguments fit. */
public class AxlOverloadTest extends LightJavaCodeInsightFixtureTestCase {

    private static final String BY_COUNT = """
            package app

            fn f(a: Int) -> Int = a

            fn f(a: Int, b: Int) -> Int = a + b

            fn main() {
                println(f(1))
                println(f(1, 2))
            }
            """;

    @Override
    protected @NotNull LightProjectDescriptor getProjectDescriptor() {
        return JAVA_21;
    }

    public void testFindsUsagesOfEachOverload() {
        myFixture.configureByText("O.axl", BY_COUNT);
        List<AxlFunction> functions = ((AxlFile) myFixture.getFile()).functions();
        assertEquals(1, myFixture.findUsages(functions.get(0)).size());
        assertEquals(1, myFixture.findUsages(functions.get(1)).size());
    }

    public void testRenamesOneOverload() {
        myFixture.configureByText("R.axl", BY_COUNT);
        myFixture.renameElement(((AxlFile) myFixture.getFile()).functions().getFirst(), "g");
        String text = myFixture.getFile().getText();
        assertTrue(text, text.contains("println(g(1))") && text.contains("println(f(1, 2))"));
    }

    public void testResolvesByLiteralType() {
        myFixture.configureByText("T.axl", """
                package app

                fn f(a: Int) -> Int = a

                fn f(a: String) -> String = a

                fn main() {
                    println(<caret>f("x"))
                }
                """);
        assertEquals(((AxlFile) myFixture.getFile()).functions().get(1), resolveAtCaret());
    }

    public void testResolvesImportedOverloads() {
        myFixture.addFileToProject("util/U.axl", """
                package util

                fn f(a: Int) -> Int = a

                fn f(a: Int, b: Int) -> Int = a + b
                """);
        myFixture.configureByText("Main.axl", """
                package app
                import fn util.U.f

                fn main() {
                    println(<caret>f(1, 2))
                }
                """);
        PsiElement target = resolveAtCaret();
        assertTrue(String.valueOf(target), target instanceof AxlFunction function && function.parameters().size() == 2);
    }

    private PsiElement resolveAtCaret() {
        return myFixture.getFile().findReferenceAt(myFixture.getCaretOffset()).resolve();
    }
}
