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
import org.axl.intellij.psi.AxlFile;
import org.axl.intellij.psi.AxlFunction;
import org.axl.intellij.psi.AxlTypeDeclaration;
import org.axl.intellij.psi.AxlTypeParameter;
import org.axl.intellij.psi.AxlVariable;

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
                "fn f(o: Object) -> Int = when (o) { is List<*> -> 1 is java.util.Map<*, *> -> 2 else -> 0 }\n",
                "fn f(o: Object) -> Boolean = o is List<*> && o !is Map<*, *>\n",
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
        file = myFixture.configureByText("Star.axl", "package app\n\nfn f() = HashMap<Class<*>, Map<*, String>>()\n");
        assertNull(PsiTreeUtil.findChildOfType(file, PsiErrorElement.class));
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
                "interface_method_errors/src/app/Main", "attempt_statement/src/app/Main", "elvis_throw/src/app/Main",
                "type_parameters/src/app/Main", "type_parameter_errors/src/app/Main", "type_parameter_static/src/app/Box",
                "pipe_fallback/src/app/Main", "loop_jumps/src/app/Main", "use_resources/src/app/Main",
                "enum_fields/src/app/Main", "java_superclass/src/app/Main", "data_class_methods/src/app/Main",
                "attempt_in_lambda/src/app/Main", "property_inference/src/app/Main", "result_else_narrowing/src/app/Main",
                "pipe_sorted_comparator/src/app/Main", "console_io/src/app/Main", "when_arm_statements/src/app/Main",
                "attempt_exits/src/app/Main", "private_class_parameters/src/app/Main",
                "attempt_typed_handler/src/app/Main", "class_constructors/src/app/Main", "class_constructors/src/app/Opt",
                "primary_constructors/src/app/Main")) {
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

    public void testReadsCountingLoopsAndKeepsTheirWordsAsNamesElsewhere() {
        PsiFile file = myFixture.configureByText("Count.axl", """
                package app

                fn f(d: Int, n: Int) {
                    for (k in -d..d step 2) {
                        println(k)
                    }
                    for (i in n - 1 downTo 0) {
                        println(i)
                    }
                    val until = n
                    for (j in 0 until until) {
                        println(j.toString().length())
                    }
                }
                """);
        assertNull(PsiTreeUtil.findChildOfType(file, PsiErrorElement.class));
        assertEquals(3, PsiTreeUtil.collectElements(file, e -> e.getNode().getElementType() == AxlElementTypes.FOR).length);
        assertEquals(List.of("in", "step", "in", "downTo", "in", "until"), Arrays.stream(PsiTreeUtil.collectElements(file,
                e -> e.getNode().getElementType() == AxlTokenTypes.CONTEXTUAL_KEYWORD)).map(PsiElement::getText).toList());
        assertEquals(List.of("k", "i", "j"), Arrays.stream(PsiTreeUtil.collectElements(file,
                e -> e.getNode().getElementType() == AxlElementTypes.LOOP_VARIABLE)).map(PsiElement::getText).toList());
    }

    public void testReadsUseBreakAndContinueStatements() {
        PsiFile file = myFixture.configureByText("Use.axl", """
                package app

                fn lines(text: String) -> Int {
                    var n = 0
                    use (val reader: java.io.BufferedReader = open(text)) {
                        while (true) {
                            val line = reader.readLine()
                            if (line == null) {
                                break
                            }
                            if (line.isEmpty()) {
                                continue
                            }
                            n += 1
                        }
                    } else e -> println("failed: " + e.getMessage())
                    use (val t = tracker()) {
                        println(t)
                    }
                    return n
                }
                """);
        assertNull(PsiTreeUtil.findChildOfType(file, PsiErrorElement.class));
        PsiElement[] uses = PsiTreeUtil.collectElements(file, e -> e.getNode().getElementType() == AxlElementTypes.USE);
        assertEquals(2, uses.length);
        assertEquals(2, PsiTreeUtil.collectElements(file, e -> e.getNode().getElementType() == AxlElementTypes.JUMP).length);
        List<AxlVariable> declared = PsiTreeUtil.getChildrenOfTypeAsList(uses[0], AxlVariable.class);
        assertEquals(List.of("reader", "e"), declared.stream().map(AxlVariable::getName).toList());
        assertEquals(List.of(AxlElementTypes.VARIABLE, AxlElementTypes.LAMBDA_PARAMETER),
                declared.stream().map(v -> v.getNode().getElementType()).toList());
        assertEquals("java.io.BufferedReader", declared.getFirst().type().getText());
        assertEquals(List.of("t"), PsiTreeUtil.getChildrenOfTypeAsList(uses[1], AxlVariable.class).stream().map(AxlVariable::getName).toList());
        assertEquals(List.of("use", "use"), Arrays.stream(PsiTreeUtil.collectElements(file,
                e -> e.getNode().getElementType() == AxlTokenTypes.CONTEXTUAL_KEYWORD)).map(PsiElement::getText).toList());
    }

    public void testReadsJumpsAndAssignmentsAsTheArmsOfAWhenStatement() {
        PsiFile file = myFixture.configureByText("Arms.axl", """
                package app

                class Counter(var count: Int)

                fn f(xs: java.util.List<Int>, c: Counter) -> Int {
                    var n = 0
                    for (x in xs) {
                        when (x) {
                            2 -> continue
                            4 -> break
                            1 -> n = x
                            else -> n += x
                        }
                        when (x) {
                            3 -> c.count += 10; else -> c.count = n
                        }
                    }
                    return when (n) { 0 -> -1 else -> n }
                }
                """);
        assertNull(PsiTreeUtil.findChildOfType(file, PsiErrorElement.class));
        PsiElement[] jumps = PsiTreeUtil.collectElements(file, e -> e.getNode().getElementType() == AxlElementTypes.JUMP);
        assertEquals(List.of("continue", "break"), Arrays.stream(jumps).map(PsiElement::getText).toList());
        PsiElement[] assignments = PsiTreeUtil.collectElements(file, e -> e.getNode().getElementType() == AxlElementTypes.ASSIGNMENT);
        assertEquals(List.of("n = x", "n += x", "c.count += 10", "c.count = n"), Arrays.stream(assignments).map(PsiElement::getText).toList());
        assertTrue(Arrays.stream(assignments).allMatch(a -> a.getParent().getNode().getElementType() == AxlElementTypes.WHEN_ARM));
        assertEquals(8, PsiTreeUtil.collectElements(file, e -> e.getNode().getElementType() == AxlElementTypes.WHEN_ARM).length);
        assertErrors("fn f(n: Int) {\n    when (n) {\n        1 -> n + 1 = 2\n    }\n}\n");
    }

    public void testReadsReturnAsTheArmOfAWhenStatement() {
        PsiFile file = myFixture.configureByText("Returns.axl", """
                package app

                fn f(n: Int) -> String {
                    when (n) {
                        1 -> return "one"
                        2 -> println("two")
                    }
                    return n.toString()
                }

                fn g(n: Int) {
                    when (n) {
                        1 -> println("one")
                        else -> return
                    }
                    println(n)
                }
                """);
        assertNull(PsiTreeUtil.findChildOfType(file, PsiErrorElement.class));
        PsiElement[] returns = PsiTreeUtil.collectElements(file, e -> e.getNode().getElementType() == AxlElementTypes.RETURN
                && e.getParent().getNode().getElementType() == AxlElementTypes.WHEN_ARM);
        assertEquals(List.of("return \"one\"", "return"), Arrays.stream(returns).map(PsiElement::getText).toList());
    }

    public void testReadsBreakContinueAndReturnAsTheHandlerOfAnAttemptStatement() {
        PsiFile file = myFixture.configureByText("Exits.axl", """
                package app

                fn send(i: Int) {
                    println(i)
                }

                fn f(xs: java.util.List<Int>) -> Int {
                    for (x in xs) {
                        attempt send(x) else e -> continue
                        attempt send(x) else break;
                        attempt send(x) else e -> return e.getMessage().length()
                    }
                    attempt send(0) else return -1
                    attempt send(1) else e -> return
                    return 1
                }
                """);
        assertNull(PsiTreeUtil.findChildOfType(file, PsiErrorElement.class));
        PsiElement[] attempts = PsiTreeUtil.collectElements(file, e -> e.getNode().getElementType() == AxlElementTypes.ATTEMPT);
        assertEquals(5, attempts.length);
        PsiElement[] jumps = PsiTreeUtil.collectElements(file, e -> e.getNode().getElementType() == AxlElementTypes.JUMP);
        assertEquals(List.of("continue", "break"), Arrays.stream(jumps).map(PsiElement::getText).toList());
        PsiElement[] returns = PsiTreeUtil.collectElements(file, e -> e.getNode().getElementType() == AxlElementTypes.RETURN
                && e.getParent().getNode().getElementType() == AxlElementTypes.ATTEMPT);
        assertEquals(List.of("return e.getMessage().length()", "return -1", "return"), Arrays.stream(returns).map(PsiElement::getText).toList());
        assertTrue(Arrays.stream(jumps).allMatch(e -> e.getParent().getNode().getElementType() == AxlElementTypes.ATTEMPT));
        assertErrors("fn f() -> Int {\n    val r = attempt g() else e -> return 1\n    return 2\n}\n");
        assertErrors("fn f() {\n    for (x in xs) {\n        (attempt g() else break)\n    }\n}\n");
    }

    public void testKeepsUseAsANameOutsideTheStatement() {
        PsiFile file = myFixture.configureByText("Names.axl", "package app\n\nfn use(n: Int) -> Int = n\n\nfn f(a: Int) -> Int {\n"
                + "    val use = use(a)\n    return use + use(1)\n}\n");
        assertNull(PsiTreeUtil.findChildOfType(file, PsiErrorElement.class));
        assertEquals(0, PsiTreeUtil.collectElements(file, e -> e.getNode().getElementType() == AxlTokenTypes.CONTEXTUAL_KEYWORD).length);
        assertEquals(0, PsiTreeUtil.collectElements(file, e -> e.getNode().getElementType() == AxlElementTypes.USE).length);
    }

    public void testReadsEnumFieldsAndTheArgumentsOfConstants() {
        PsiFile file = myFixture.configureByText("Planets.axl", """
                package app

                enum Planet(val mass: Double, val radius: Double) {
                    MERCURY(3.303e23, 2.4397e6),
                    EARTH(5.976e24, 6.37814e6),
                }

                enum Level(val code: Int, val label: String?) { LOW(1, "low"), HIGH(3, null) }

                enum Color { RED, GREEN }
                """);
        assertNull(PsiTreeUtil.findChildOfType(file, PsiErrorElement.class));
        List<AxlTypeDeclaration> enums = ((AxlFile) file).typeDeclarations();
        assertEquals(List.of("mass", "radius", "MERCURY", "EARTH"), enums.getFirst().fields().stream().map(AxlVariable::getName).toList());
        assertEquals(List.of(AxlElementTypes.FIELD, AxlElementTypes.FIELD, AxlElementTypes.ENUM_CONSTANT, AxlElementTypes.ENUM_CONSTANT),
                enums.getFirst().fields().stream().map(f -> f.getNode().getElementType()).toList());
        assertEquals("Double", enums.getFirst().fields().getFirst().type().getText());
        assertEquals("String?", enums.get(1).fields().get(1).type().getText());
        assertEquals(4, PsiTreeUtil.collectElements(file, e -> e.getNode().getElementType() == AxlElementTypes.ARGUMENTS).length);
        assertEquals(6, PsiTreeUtil.collectElements(file, e -> e.getNode().getElementType() == AxlElementTypes.ENUM_CONSTANT).length);
        assertEquals(List.of("RED", "GREEN"), enums.get(2).fields().stream().map(AxlVariable::getName).toList());
    }

    public void testReadsTheConstructorArgumentsOfAJavaSuperclass() {
        PsiFile file = myFixture.configureByText("Errors.axl", """
                package app

                class NotFound(val id: String) : RuntimeException("not found: " + id), java.io.Serializable

                class Worker : Thread("w") {
                    fn run() {
                        println(getName())
                    }
                }

                class Recent(val limit: Int) : java.util.LinkedHashMap<String, Int>() {
                    fn removeEldestEntry(eldest: java.util.Map.Entry<String, Int>) -> Boolean = size() > limit
                }
                """);
        assertNull(PsiTreeUtil.findChildOfType(file, PsiErrorElement.class));
        List<AxlTypeDeclaration> classes = ((AxlFile) file).typeDeclarations();
        assertEquals(3, classes.size());
        for (AxlTypeDeclaration cls : classes) {
            List<PsiElement> arguments = Arrays.stream(cls.getChildren())
                    .filter(c -> c.getNode().getElementType() == AxlElementTypes.ARGUMENTS).toList();
            assertEquals(cls.getName(), 1, arguments.size());
            assertEquals(cls.getName(), AxlElementTypes.TYPE, arguments.getFirst().getPrevSibling().getNode().getElementType());
        }
        assertEquals(List.of("RuntimeException", "java.io.Serializable"),
                PsiTreeUtil.getChildrenOfTypeAsList(classes.getFirst(), org.axl.intellij.psi.AxlType.class).stream()
                        .map(PsiElement::getText).toList());
        assertEquals(List.of("run"), classes.get(1).methods().stream().map(AxlFunction::getName).toList());
    }

    public void testReadsTheMethodsOfADataClassBody() {
        PsiFile file = myFixture.configureByText("Money.axl", """
                package app

                data class Money(val cents: Long) : Comparable<Money> {
                    fn compareTo(other: Money) -> Int = Long.compare(cents, other.cents())

                    @Override
                    private fn show() -> String {
                        return cents + ""
                    }
                }

                data class Plain(val x: Int) : Named
                """);
        assertNull(PsiTreeUtil.findChildOfType(file, PsiErrorElement.class));
        AxlTypeDeclaration money = ((AxlFile) file).typeDeclarations().getFirst();
        assertEquals(List.of("cents"), money.fields().stream().map(AxlVariable::getName).toList());
        assertEquals(List.of("compareTo", "show"), money.methods().stream().map(AxlFunction::getName).toList());
        assertTrue(money.methods().get(1).isPrivate());
        assertFalse(money.methods().getFirst().isAbstract());
        assertEquals(List.of(), ((AxlFile) file).typeDeclarations().get(1).methods());
    }

    public void testRejectsWhatTheGrammarRejectsInTheNewStatementsAndMembers() {
        assertErrors("fn f() {\n    use (reader = open()) {\n    }\n}\n");
        assertErrors("fn f() {\n    use (val reader = open())\n}\n");
        assertErrors("fn f() {\n    use (val reader = open()) {\n    } else\n}\n");
        assertErrors("enum E(x: Int) { A(1) }\n");
        assertErrors("enum E(val x: Int) { A(1 }\n");
        assertErrors("data class P(val x: Int) {\n    val y: Int = 1\n}\n");
        assertErrors("data class P(val x: Int) : Shape()\n");
        assertErrors("interface I : Named()\n");
        assertErrors("class C : Base(1,) {}\n");
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
        assertErrors("fn f<>() = 1\n");
        assertErrors("data class P<T(val x: T)\n");
        assertErrors("class C<T : > {}\n");
    }

    public void testReadsTypeParametersAsDeclarationsOfTheirOwners() {
        PsiFile file = myFixture.configureByText("Generic.axl", """
                package app

                data class Pair<A, B>(val first: A, val second: B) : Shape<A>

                class Stack<T : Object> {
                    val items: List<T> = ArrayList<T>()
                    fn push(item: T) {
                    }
                }

                interface Repository<K, V : Comparable<V>> {
                    fn find(key: K) -> V?
                    fn map<R>(f: Function<V, R>) -> List<R>
                }

                fn largest<T : Comparable<T>>(xs: List<T>) -> T? = null

                fn swap<A, B>(p: Pair<A, B>) -> Pair<B, A> = Pair<B, A>(p.second(), p.first())
                """);
        assertNull(PsiTreeUtil.findChildOfType(file, PsiErrorElement.class));
        List<AxlTypeDeclaration> types = ((AxlFile) file).typeDeclarations();
        assertEquals(List.of("A", "B"), names(types.get(0).typeParameters()));
        assertEquals(List.of("T"), names(types.get(1).typeParameters()));
        assertEquals("Object", types.get(1).typeParameters().getFirst().bound().getText());
        assertEquals(List.of("K", "V"), names(types.get(2).typeParameters()));
        assertNull(types.get(2).typeParameters().getFirst().bound());
        assertEquals("Comparable<V>", types.get(2).typeParameters().get(1).bound().getText());
        assertEquals(List.of(), names(types.get(2).methods().getFirst().typeParameters()));
        assertEquals(List.of("R"), names(types.get(2).methods().get(1).typeParameters()));
        AxlFunction largest = ((AxlFile) file).functions().getFirst();
        assertEquals("Comparable<T>", largest.typeParameters().getFirst().bound().getText());
        assertEquals("T?", largest.returnType().getText());
        assertEquals(List.of("xs"), largest.parameters().stream().map(AxlVariable::getName).toList());
        assertEquals(List.of("A", "B"), names(((AxlFile) file).functions().get(1).typeParameters()));
        assertEquals(9, PsiTreeUtil.collectElements(file, e -> e.getNode().getElementType() == AxlElementTypes.TYPE_PARAMETER).length);
    }

    private static List<String> names(List<AxlTypeParameter> parameters) {
        return parameters.stream().map(AxlTypeParameter::getName).toList();
    }

    public void testReadsAFallbackAfterAPipeAsTheFallbackOfTheWholePipe() {
        PsiFile file = myFixture.configureByText("Pipe.axl", """
                package app

                fn top(names: List<String>) -> String = names |> sorted |> first ?: "none"

                fn big(xs: List<Int>) -> Int = (xs |> filter { it > 4 } |> first ?: 0) + 1

                fn plain(s: String?) -> String = s ?: "x"
                """);
        assertNull(PsiTreeUtil.findChildOfType(file, PsiErrorElement.class));
        PsiElement[] fallbacks = PsiTreeUtil.collectElements(file, e -> e.getNode().getElementType() == AxlElementTypes.ELVIS);
        assertEquals(List.of(AxlElementTypes.PIPE, AxlElementTypes.PIPE, AxlElementTypes.REFERENCE),
                Arrays.stream(fallbacks).map(e -> e.getFirstChild().getNode().getElementType()).toList());
        assertEquals(2, PsiTreeUtil.collectElements(fallbacks[0], e -> e.getNode().getElementType() == AxlElementTypes.PIPE_STAGE).length);
        assertEquals(AxlElementTypes.LITERAL, fallbacks[0].getLastChild().getNode().getElementType());
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
