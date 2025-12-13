package org.axl.intellij;

import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiJavaFile;
import com.intellij.psi.PsiMethod;
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

    public void testFindsJavaCallsOfOverloadsWithTheSameParameterCount() {
        myFixture.addFileToProject("app/O.axl", """
                package app

                fn f(a: Int) -> Int = a

                fn f(a: String) -> String = a
                """);
        myFixture.addFileToProject("app/Caller.java", """
                package app;

                class Caller {
                    Object call() {
                        return O.f("x");
                    }
                }
                """);
        myFixture.configureFromTempProjectFile("app/O.axl");
        List<AxlFunction> functions = ((AxlFile) myFixture.getFile()).functions();
        assertEquals(0, myFixture.findUsages(functions.get(0)).size());
        assertEquals(1, myFixture.findUsages(functions.get(1)).size());
    }

    public void testRenamesOneJavaOverloadWithoutTheCallsOfAnother() {
        PsiJavaFile util = (PsiJavaFile) myFixture.addFileToProject("app/Util.java", """
                package app;

                public final class Util {
                    public static String f(String s) { return s; }
                    public static long f(long x) { return x; }
                }
                """);
        myFixture.configureByText("M.axl", "package app\n\nfn main() {\n    println(Util.f(1))\n    println(Util.f(\"s\"))\n}\n");
        PsiMethod byString = util.getClasses()[0].getMethods()[0];
        assertEquals(1, myFixture.findUsages(byString).size());
        myFixture.renameElement(byString, "fs");
        String text = myFixture.getFile().getText();
        assertTrue(text, text.contains("Util.f(1)") && text.contains("Util.fs(\"s\")"));
    }

    public void testResolvesJavaOverloadsAsJavacDoes() {
        myFixture.addFileToProject("app/Util.java", """
                package app;

                public final class Util {
                    public static String f(String s) { return s; }
                    public static long f(long x) { return x; }
                    public static int g(int x) { return x; }
                    public static long g(long x) { return x; }
                    public static Object h(java.util.List<String> xs) { return xs; }
                    public static Object h(String s) { return s; }
                    public static int k(int... xs) { return 0; }
                    public static int k(int a, int b) { return 0; }
                }
                """);
        myFixture.configureByText("M.axl", """
                package app

                fn main() {
                    val xs: java.util.ArrayList<String> = java.util.ArrayList()
                    println(Util.f(1))
                    println(Util.g(1))
                    println(Util.h(xs))
                    println(Util.k(1, 2))
                }
                """);
        assertEquals("f(long x)", resolvedSignature("f(1)"));
        assertEquals("g(int x)", resolvedSignature("g(1)"));
        assertEquals("h(java.util.List<String> xs)", resolvedSignature("h(xs)"));
        assertEquals("k(int a, int b)", resolvedSignature("k(1, 2)"));
    }

    private String resolvedSignature(String call) {
        int offset = myFixture.getFile().getText().indexOf(call);
        PsiElement target = myFixture.getFile().findReferenceAt(offset).resolve();
        assertNotNull(call, target);
        PsiMethod method = (PsiMethod) target;
        return method.getName() + method.getParameterList().getText().replaceAll("\\s+", " ");
    }

    private PsiElement resolveAtCaret() {
        return myFixture.getFile().findReferenceAt(myFixture.getCaretOffset()).resolve();
    }
}
