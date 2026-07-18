package org.axl.intellij;

import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiErrorElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.axl.intellij.lexer.AxlTokenTypes;
import org.axl.intellij.parser.AxlElementTypes;
import org.axl.intellij.psi.AxlFunction;
import org.axl.intellij.psi.AxlTypeDeclaration;

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
                "fn f() = { a, b -> a }\n", "fn f(x: Boolean) = !!x\n", "fn f() {\n    a.b = 1\n    c = 2\n}\n",
                "fn f(s: Shape) -> String = when (s) { is Circle -> \"c\" is app.Square -> \"s\" }\n",
                "fn f(s: Shape) -> Boolean = when (s) { is Circle -> s is Circle else -> false }\n",
                "fn f() = java.util.HashMap<String, Int>()\n", "fn f() = java.util.ArrayList<String>()\n",
                "fn f(a: A, c: Int) = a.b < c\n",
                "fn f(n: Int) -> Function<String, Int> = when (n) {\n    1 -> { s: String -> s.length() }\n"
                        + "    2 -> { a, b: java.util.Map<String, Int?> -> 0 }\n    else -> { 0 }\n}\n")) {
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

    public void testExplainsIfsWithBracesWhereAValueIsWantedAndWithoutWhereAStatementIs() {
        Map<String, String> messages = Map.of(
                "fn f(c: Boolean) {\n    if (c) println(\"a\") else println(\"b\")\n}\n", "an `if` statement needs braces",
                "fn f(c: Boolean) -> Int = if (c) { 1 } else 2\n", "an `if` that gives a value takes expressions");
        messages.forEach((body, message) -> {
            PsiFile file = myFixture.configureByText("If.axl", "package app\n\n" + body);
            assertNull(body, PsiTreeUtil.findChildOfType(file, PsiErrorElement.class));
            List<String> errors = myFixture.doHighlighting().stream().filter(h -> h.getSeverity() == HighlightSeverity.ERROR)
                    .map(h -> h.getDescription()).toList();
            assertTrue(body + errors, errors.stream().anyMatch(e -> e.contains(message)));
        });
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
        file = myFixture.configureByText("Qualified.axl", "package app\n\nfn f() = java.util.HashMap<String, Int>()\n");
        assertEquals(1, PsiTreeUtil.collectElements(file, e -> e.getNode().getElementType() == AxlElementTypes.GENERIC_NEW).length);
    }

    public void testReadsABraceAfterAWhenArmWithAnArrowInsideAsALambda() {
        PsiFile file = myFixture.configureByText("Arm.axl",
                "package app\n\nfn f(n: Int) -> Function<String, Int> = when (n) {\n    1 -> { s: String -> s.length() }\n    else -> { 0 }\n}\n");
        assertEquals(1, PsiTreeUtil.collectElements(file, e -> e.getNode().getElementType() == AxlElementTypes.LAMBDA).length);
        assertEquals(1, PsiTreeUtil.collectElements(file, e -> e.getNode().getElementType() == AxlElementTypes.BLOCK).length);
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
                "is_smart_cast/src/app/Main", "when_expressions/src/app/Main", "classes/src/app/Counter",
                "classes/src/app/Main", "enums/src/app/Main", "conditional_and_throw/src/app/Main",
                "if_expression_braces/src/app/Main", "when_subjects/src/app/Main", "class_members/src/app/Main",
                "interface_members/src/app/Main", "value_branches/src/app/Main", "annotations/src/app/Main",
                "class_literals/src/app/Main", "wildcard_types/src/app/Main", "attempt_throws/src/app/Main",
                "interface_methods/src/app/Main", "interface_file_type/src/app/Store", "interface_file_type/src/app/Main",
                "interface_method_errors/src/app/Main", "attempt_statement/src/app/Main", "elvis_throw/src/app/Main")) {
            String text = Files.readString(Path.of("..", "tests", "conformance", name + ".axl"));
            PsiFile file = myFixture.configureByText("Case.axl", text);
            assertNull(name, PsiTreeUtil.findChildOfType(file, PsiErrorElement.class));
        }
    }

    public void testParsesClassesEnumsConditionalsAndThrow() {
        PsiFile file = myFixture.configureByText("Kinds.axl", """
                package app

                private enum Mode { ON, OFF, }

                class Counter(val name: String, var step: Int) : Named {
                    var count: Int = 0
                    private val label: String = "c"
                    fn next() -> Int = when (step) {
                        1 -> count
                        else -> throw IllegalStateException("x")
                    }
                    fn sign() -> String = if (step > 0) "positive" else "other"
                }

                class Empty
                """);
        assertNull(PsiTreeUtil.findChildOfType(file, PsiErrorElement.class));
        assertEquals(1, PsiTreeUtil.collectElements(file, e -> e.getNode().getElementType() == AxlElementTypes.ENUM).length);
        assertEquals(2, PsiTreeUtil.collectElements(file, e -> e.getNode().getElementType() == AxlElementTypes.CLASS).length);
        assertEquals(2, PsiTreeUtil.collectElements(file,
                e -> e.getNode().getElementType() == AxlElementTypes.ENUM_CONSTANT).length);
        assertEquals(1, PsiTreeUtil.collectElements(file, e -> e.getNode().getElementType() == AxlElementTypes.THROW).length);
        assertEquals(1, PsiTreeUtil.collectElements(file,
                e -> e.getNode().getElementType() == AxlElementTypes.CONDITIONAL).length);
    }

    public void testReadsAnnotationsIntoTheirDeclarationsWithClassLiteralsAndStarArguments() {
        PsiFile file = myFixture.configureByText("Marked.axl", """
                package app

                @Marker(value = "class", weight = -1, type = Int.class, tags = {"a", {"b"}})
                class Counter(@Marker("x") val start: Int) {
                    @Deprecated
                    var count: Int = start

                    @Override
                    fn toString() -> String = "Counter " + count
                }

                @org.junit.jupiter.api.Test
                fn check(@Marker("p") c: Class<*>, m: java.util.Map<String, *>) -> Class<*> = String.class

                interface Named {
                    @Marker val name: String
                }
                """);
        assertNull(PsiTreeUtil.findChildOfType(file, PsiErrorElement.class));
        PsiElement[] annotations = PsiTreeUtil.collectElements(file, e -> e.getNode().getElementType() == AxlElementTypes.ANNOTATION);
        assertEquals(List.of(AxlElementTypes.CLASS, AxlElementTypes.FIELD, AxlElementTypes.VARIABLE, AxlElementTypes.FUNCTION,
                AxlElementTypes.FUNCTION, AxlElementTypes.PARAMETER, AxlElementTypes.FIELD),
                Arrays.stream(annotations).map(a -> a.getParent().getNode().getElementType()).toList());
        assertEquals(2, PsiTreeUtil.collectElements(file, e -> e.getNode().getElementType() == AxlElementTypes.ANNOTATION_ARRAY).length);
        assertEquals(2, PsiTreeUtil.collectElements(file, e -> e.getNode().getElementType() == AxlElementTypes.CLASS_LITERAL).length);
        assertEquals("Counter", ((AxlTypeDeclaration) annotations[0].getParent()).getName());
        assertEquals("check", ((AxlFunction) annotations[4].getParent()).getName());
    }

    public void testReadsTheMethodsOfAnInterfaceAsFunctionsWithoutBodies() {
        PsiFile file = myFixture.configureByText("Notes.axl", """
                package app

                interface Notes : Named {
                    val name: String
                    @Marker fn add(text: String, at: Int) -> Int
                    fn find(id: Int) -> String?;
                    fn clear()
                    fn size() -> Int
                }
                """);
        assertNull(PsiTreeUtil.findChildOfType(file, PsiErrorElement.class));
        AxlTypeDeclaration notes = ((org.axl.intellij.psi.AxlFile) file).typeDeclarations().getFirst();
        assertEquals(List.of("add", "find", "clear", "size"), notes.methods().stream().map(AxlFunction::getName).toList());
        assertEquals(List.of("name"), notes.fields().stream().map(f -> f.getName()).toList());
        assertTrue(notes.methods().stream().allMatch(AxlFunction::isAbstract));
        assertTrue(notes.methods().stream().allMatch(m -> m.body() == null));
        assertEquals(List.of(2, 1, 0, 0), notes.methods().stream().map(m -> m.parameters().size()).toList());
        assertEquals(List.of("Int", "String?", "", "Int"),
                notes.methods().stream().map(m -> m.returnType() == null ? "" : m.returnType().getText()).toList());
        assertEquals(1, PsiTreeUtil.collectElements(notes.methods().getFirst(),
                e -> e.getNode().getElementType() == AxlElementTypes.ANNOTATION).length);
    }

    public void testReadsAThrowAfterAnElvisAndAnAttemptStatementWithAVoidCall() {
        PsiFile file = myFixture.configureByText("Throws.axl", """
                package app

                fn f(s: String?, m: Map<String, String>) -> String {
                    val a = s ?: throw IllegalStateException("missing")
                    attempt close() else e -> throw IllegalStateException("cannot close", e)
                    attempt close() else e -> println(e.getMessage())
                    attempt close() else println("failed")
                    return m.get("a") ?: m.get("b") ?: throw IllegalArgumentException("none")
                }
                """);
        assertNull(PsiTreeUtil.findChildOfType(file, PsiErrorElement.class));
        assertEquals(3, PsiTreeUtil.collectElements(file, e -> e.getNode().getElementType() == AxlElementTypes.ELVIS).length);
        assertEquals(3, PsiTreeUtil.collectElements(file, e -> e.getNode().getElementType() == AxlElementTypes.ATTEMPT).length);
        assertEquals(3, PsiTreeUtil.collectElements(file, e -> e.getNode().getElementType() == AxlElementTypes.THROW).length);
    }

    public void testReadsAnIfWithBracesAsAStatement() {
        PsiFile file = myFixture.configureByText("Stmt.axl", "package app\n\nfn f(n: Int) {\n    if (n > 0) {\n        println(n)\n    }\n}\n");
        assertNull(PsiTreeUtil.findChildOfType(file, PsiErrorElement.class));
        assertEquals(0, PsiTreeUtil.collectElements(file,
                e -> e.getNode().getElementType() == AxlElementTypes.CONDITIONAL).length);
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
        assertErrors("class C(x: Int)\n");
        assertErrors("class C {\n    data\n}\n");
        assertErrors("enum E { A B }\n");
        assertErrors("enum E { A\n");
        assertErrors("fn f(n: Int) = if (n > 0) 1\n");
        assertErrors("fn f(xs: List<Int>) {\n    for (x xs) {\n    }\n}\n");
        assertErrors("fn f(xs: List<Int>) {\n    for (x in xs) println(x)\n}\n");
        assertErrors("fn f(n: Int) = when (n) {\n    - foo -> 1\n}\n");
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
