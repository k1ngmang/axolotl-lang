package org.axl.intellij.actions;

import com.intellij.psi.PsiJavaFile;
import com.intellij.testFramework.LightProjectDescriptor;
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase;
import org.jetbrains.annotations.NotNull;

/** Java outside the convertible subset is refused instead of turning into invalid Axolotl. */
public class JavaToAxolotlTest extends LightJavaCodeInsightFixtureTestCase {

    @Override
    protected @NotNull LightProjectDescriptor getProjectDescriptor() {
        return JAVA_21;
    }

    public void testRefusesWhatAxolotlCannotExpress() {
        assertRefused("Default.java", "public final class Default {\n    public static int one() { return 1; }\n}\n");
        assertRefused("Bits.java", inClass("public static int f(int a, int b) { return a & b; }"));
        assertRefused("Shift.java", inClass("public static int f(int a) { int x = a; x <<= 1; return x; }"));
        assertRefused("Chain.java", inClass("public static int f(int a) { int x = 0; int y = 0; x = y = a; return x; }"));
        assertRefused("Wild.java", inClass("public static int f(java.util.List<? extends Number> xs) { return xs.size(); }"));
        assertRefused("HexFloat.java", inClass("public static double f() { return 0x1p3; }"));
        assertRefused("Keyword.java", inClass("public static int f(int val) { return val; }"));
        assertRefused("Args.java", inClass("public static void main(String[] args) { System.out.println(args.length); }"));
        assertRefused("Generic.java", inClass("public static <T> T id(T x) { return x; }"));
        assertRefused("Init.java", inClass("static { System.out.println(\"loaded\"); }"));
        assertRefused("Ctor.java", inClass("private Ctor() { System.out.println(\"x\"); }"));
    }

    public void testRefusesWhatTheTranslatorRejects() {
        assertRefused("Dollar.java", inClass("public static int f(int a$b) { return a$b; }"));
        assertRefused("JavaName.java", inClass("public static int f(int java) { return java; }"));
        assertRefused("MainArgs.java", inClass("public static void main(String[] a) { int args = 1; System.out.println(args); }"));
        assertRefused("Result.java", inClass("public static int f() { return 1; }"));
        assertRefused("Order.java", inClass("static final int A = compute();\nstatic final int B = 2;\n"
                + "static int compute() { return B; }"));
    }

    public void testDeclaresVariablesThatHoldNullNullable() {
        String axl = convert("Maybe.java", inClass("""
                static final String NONE = null;
                public static void f(boolean b) {
                    String r = null;
                    if (b) {
                        r = "x";
                    }
                    String s = "y";
                    System.out.println(r + s + NONE);
                }"""));
        assertTrue(axl, axl.contains("val NONE: String? = null\n"));
        assertTrue(axl, axl.contains("var r: String? = null\n"));
        assertTrue(axl, axl.contains("val s: String = \"y\"\n"));
    }

    public void testConvertsLiteralsToTheirAxolotlForm() {
        String axl = convert("Lits.java", inClass("""
                public static double f() {
                    final int b = 0b101;
                    final int o = 017;
                    final int u = 0_7;
                    final double d = .5;
                    final double e = 5.;
                    return b + o + d + e;
                }"""));
        assertTrue(axl, axl.contains("val b: Int = 5\n"));
        assertTrue(axl, axl.contains("val o: Int = 15\n"));
        assertTrue(axl, axl.contains("val u: Int = 7\n"));
        assertTrue(axl, axl.contains("val d: Double = 0.5\n"));
        assertTrue(axl, axl.contains("val e: Double = 5.0\n"));
    }

    public void testImportsOverloadedStaticMethodsAsFunctions() {
        String axl = convert("Static.java", "package util;\nimport static java.lang.Math.max;\n"
                + "public final class Static {\n    public static int f(int a) { return max(a, 1); }\n}\n");
        assertTrue(axl, axl.contains("import fn java.lang.Math.max\n"));
    }

    /** A class named after its file, which the helpers below fill in for `CLASS`. */
    private static String inClass(String member) {
        return "package util;\npublic final class CLASS {\n" + member + "\n}\n";
    }

    private PsiJavaFile configure(String fileName, String java) {
        String text = java.replace("CLASS", fileName.replace(".java", ""));
        return (PsiJavaFile) myFixture.configureByText(fileName, text);
    }

    private String convert(String fileName, String java) {
        return JavaToAxolotl.convert(configure(fileName, java));
    }

    private void assertRefused(String fileName, String java) {
        assertFalse(fileName, JavaToAxolotl.isConvertible(configure(fileName, java)));
    }
}
