package org.axl.intellij;

import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.openapi.editor.colors.TextAttributesKey;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiField;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiModifier;
import com.intellij.psi.PsiReference;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.testFramework.LightProjectDescriptor;
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import org.axl.intellij.highlight.AxlSyntaxHighlighter;
import org.axl.intellij.psi.AxlFile;
import org.axl.intellij.psi.AxlTypeDeclaration;
import org.jetbrains.annotations.NotNull;

/** Data classes, classes, enums and interfaces, as Java code and other .axl files see them. */
public class AxlTypesTest extends LightJavaCodeInsightFixtureTestCase {

    static final String SHAPES = """
            package app

            sealed interface Shape {
                val name: String
            }

            data class Circle(val name: String, val radius: Double) : Shape
            data class Square(val name: String, val side: Double, val label: String?) : Shape

            enum Color { RED, GREEN, BLUE, }

            fn area(shape: Shape) -> Double = when (shape) {
                is Circle -> 3.14 * shape.radius() * shape.radius()
                is Square -> shape.side() * shape.side()
            }

            fn main() {
                val c = Circle("c", 2.0)
                println(c.radius())
                val color = Color.RED
                println(color.name())
            }
            """;

    static final String COUNTER = """
            package app
            import java.util.ArrayList

            class Counter(val name: String, var step: Int) {
                var count: Int = 0
                val history: ArrayList<Int> = ArrayList<Int>()

                fn next() -> Int {
                    count += step
                    history.add(count)
                    return count
                }
            }

            fn start(name: String) -> Counter = Counter(name, 1)

            fn main() {
                val c = Counter("c", 1)
                println(c.next())
                c.step = 5
                val d = start("d")
                println(d.count)
            }
            """;

    static final String MAIN = """
            package app
            import app.Shapes.Circle
            import app.Shapes.Color
            import app.Shapes.Shape

            fn main() {
                val c = Circle("m", 3.0)
                println(c.radius())
                val counter = Counter.start("x")
                println(counter.next())
                val shape: Shape = c
                val color = Color.GREEN
                println(color.ordinal() + Shapes.area(shape))
                val k = Counter("k", 2)
                println(k.count)
            }
            """;

    @Override
    protected @NotNull LightProjectDescriptor getProjectDescriptor() {
        return JAVA_21;
    }

    @Override
    protected void setUp() throws Exception {
        super.setUp();
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

    public void testJavaSeesNestedTypesConstructorsAndInstanceMembers() {
        myFixture.addFileToProject("app/Shapes.axl", SHAPES);
        myFixture.addFileToProject("app/Counter.axl", COUNTER);
        myFixture.configureByText("UseFromJava.java", """
                package app;

                class UseFromJava {
                    double f() {
                        Shapes.Circle c = new Shapes.Circle("a", 1.0);
                        Shapes.Color color = Shapes.Color.RED;
                        Shapes.Shape shape = c;
                        Counter counter = new Counter("c", 1);
                        counter.step = 5;
                        int n = counter.next() + counter.step;
                        Counter other = Counter.start("x");
                        return c.radius() + Shapes.area(c) + n + color.ordinal() + other.count + shape.name().length();
                    }
                }
                """);
        assertEquals(List.of(), myFixture.doHighlighting().stream()
                .filter(h -> h.getSeverity() == HighlightSeverity.ERROR).map(h -> h.getDescription()).toList());
    }

    public void testCopiesTheModifiersOfTheGeneratedClasses() {
        myFixture.addFileToProject("app/Shapes.axl", SHAPES);
        myFixture.addFileToProject("app/Counter.axl", COUNTER);
        PsiClass counter = findClass("app.Counter");
        assertEquals(1, counter.getConstructors().length);
        assertFalse(counter.findMethodsByName("next", false)[0].hasModifierProperty(PsiModifier.STATIC));
        assertTrue(counter.findMethodsByName("start", false)[0].hasModifierProperty(PsiModifier.STATIC));
        PsiField step = counter.findFieldByName("step", false);
        assertFalse(step.hasModifierProperty(PsiModifier.STATIC) || step.hasModifierProperty(PsiModifier.FINAL));
        assertTrue(counter.findFieldByName("name", false).hasModifierProperty(PsiModifier.FINAL));

        PsiClass shapes = findClass("app.Shapes");
        assertEquals(List.of("Shape", "Circle", "Square", "Color"),
                Arrays.stream(shapes.getInnerClasses()).map(PsiClass::getName).toList());
        assertTrue(findClass("app.Shapes.Shape").isInterface());
        assertTrue(findClass("app.Shapes.Color").isEnum());
        assertSame(shapes, findClass("app.Shapes.Circle").getContainingClass());
    }

    public void testMembersNavigateToTheirDeclarations() {
        myFixture.addFileToProject("app/Shapes.axl", SHAPES);
        AxlFile counterFile = (AxlFile) myFixture.addFileToProject("app/Counter.axl", COUNTER);
        AxlTypeDeclaration counter = counterFile.typeDeclarations().getFirst();
        PsiClass light = findClass("app.Counter");
        assertSame(counter, light.getNavigationElement());
        assertSame(counter, light.getConstructors()[0].getNavigationElement());
        assertSame(counter.methods().getFirst(), light.findMethodsByName("next", false)[0].getNavigationElement());
        assertSame(counter.fields().get(2), light.findFieldByName("count", false).getNavigationElement());
        assertSame(counterFile.functions().getFirst(), light.findMethodsByName("start", false)[0].getNavigationElement());

        PsiClass circle = findClass("app.Shapes.Circle");
        PsiElement radius = circle.findMethodsByName("radius", false)[0].getNavigationElement();
        assertEquals("radius", ((AxlTypeDeclaration) radius.getParent()).fields().get(1).getName());
        assertEquals("RED", findClass("app.Shapes.Color").findFieldByName("RED", false).getNavigationElement().getText());
    }

    public void testResolvesMembersOfClassesDataClassesAndEnums() {
        AxlFile counter = (AxlFile) myFixture.configureByText("Counter.axl", COUNTER);
        AxlTypeDeclaration counterType = counter.typeDeclarations().getFirst();
        assertSame(counterType.methods().getFirst(), resolveAt("println(c.|next())"));
        assertSame(counterType.fields().get(1), resolveAt("c.|step = 5"));
        assertSame(counterType.fields().get(2), resolveAt("println(d.|count)"));
        PsiElement add = resolveAt("history.|add(count)");
        assertEquals("java.util.ArrayList", ((PsiMethod) add).getContainingClass().getQualifiedName());

        AxlFile shapes = (AxlFile) myFixture.configureByText("Shapes.axl", SHAPES);
        AxlTypeDeclaration circle = shapes.typeDeclarations().get(1);
        AxlTypeDeclaration color = shapes.typeDeclarations().get(3);
        assertSame(circle.fields().get(1), resolveAt("println(c.|radius())"));
        assertSame(circle.fields().get(1), resolveAt("3.14 * shape.|radius()"));
        assertSame(shapes.typeDeclarations().get(2).fields().get(1), resolveAt("is Square -> shape.|side()"));
        assertSame(color.fields().getFirst(), resolveAt("Color.|RED"));
        assertEquals("name", ((PsiMethod) resolveAt("color.|name()")).getName());
    }

    public void testNarrowsAfterTestsThatLeaveAndRightOfAnd() {
        AxlFile file = (AxlFile) myFixture.configureByText("Tests.axl", SHAPES + """

                fn describe(shape: Shape) -> String {
                    if (shape !is Circle) {
                        return "not round"
                    }
                    return "radius " + shape.radius()
                }

                fn label(shape: Shape) -> String {
                    if (shape is Square && shape.label() != null) {
                        return shape.label()!!
                    }
                    return "x"
                }

                fn side(o: Shape) -> Double = if (o is Square) o.side() else 0.0
                """);
        AxlTypeDeclaration square = file.typeDeclarations().get(2);
        assertSame(file.typeDeclarations().get(1).fields().get(1), resolveAt("\"radius \" + shape.|radius()"));
        assertSame(square.fields().get(2), resolveAt("&& shape.|label()"));
        assertSame(square.fields().get(2), resolveAt("return shape.|label()!!"));
        assertSame(square.fields().get(1), resolveAt("o.|side() else"));
    }

    public void testResolvesTypesAndMembersOfOtherFiles() {
        AxlFile shapes = (AxlFile) myFixture.addFileToProject("app/Shapes.axl", SHAPES);
        AxlFile counter = (AxlFile) myFixture.addFileToProject("app/Counter.axl", COUNTER);
        PsiFile main = myFixture.addFileToProject("app/Main.axl", MAIN);
        myFixture.configureFromExistingVirtualFile(main.getVirtualFile());
        AxlTypeDeclaration circle = shapes.typeDeclarations().get(1);
        assertSame(circle, resolveAt("import app.Shapes.|Circle").getNavigationElement());
        assertSame(circle, resolveAt("= |Circle(\"m\"").getNavigationElement());
        assertSame(circle.fields().get(1), resolveAt("c.|radius()"));
        assertSame(shapes.typeDeclarations().getFirst(), resolveAt("val shape: |Shape").getNavigationElement());
        assertSame(shapes.typeDeclarations().get(3).fields().get(1), resolveAt("Color.|GREEN"));
        AxlTypeDeclaration counterType = counter.typeDeclarations().getFirst();
        assertSame(counterType.methods().getFirst(), resolveAt("counter.|next()"));
        assertSame(counterType.fields().get(2), resolveAt("k.|count"));
    }

    public void testFindsAndRenamesMemberAccesses() {
        AxlFile counter = (AxlFile) myFixture.configureByText("Counter.axl", COUNTER);
        AxlTypeDeclaration counterType = counter.typeDeclarations().getFirst();
        assertEquals(1, myFixture.findUsages(counterType.methods().getFirst()).size());
        assertEquals(2, myFixture.findUsages(counterType.fields().get(1)).size());
        myFixture.renameElement(counterType.fields().get(2), "total");
        String text = myFixture.getFile().getText();
        assertTrue(text, text.contains("println(d.total)") && text.contains("total += step"));

        AxlFile shapes = (AxlFile) myFixture.configureByText("Shapes.axl", SHAPES);
        assertEquals(1, myFixture.findUsages(shapes.typeDeclarations().get(3).fields().getFirst()).size());
        myFixture.renameElement(shapes.typeDeclarations().get(1).fields().get(1), "r");
        text = myFixture.getFile().getText();
        assertTrue(text, text.contains("println(c.r())") && text.contains("shape.r() * shape.r()"));
    }

    public void testCompletesMembersOfAxolotlTypes() {
        assertCompletes(SHAPES.replace("println(c.radius())", "println(c.<caret>)"), "radius", "name");
        assertCompletes(SHAPES.replace("val color = Color.RED", "val color = Color.<caret>"), "RED", "values");
        assertCompletes(SHAPES.replace("3.14 * shape.radius()", "3.14 * shape.<caret>"), "radius");
        assertCompletes(COUNTER.replace("println(d.count)", "println(d.<caret>)"), "count", "next");
    }

    public void testOffersTheKeywordsThatFitThePlace() {
        List<String> expression = complete(SHAPES.replace("val color = Color.RED", "val color = <caret>"));
        assertTrue(expression.toString(), expression.containsAll(List.of("if", "when", "null")));
        assertFalse(expression.toString(), expression.stream().anyMatch(List.of("package", "fn", "class", "else", "return")::contains));
        List<String> topLevel = complete(SHAPES.replace("fn main() {", "<caret>\nfn main() {"));
        assertTrue(topLevel.toString(), topLevel.containsAll(List.of("fn", "class", "enum")));
        assertFalse(topLevel.toString(), topLevel.stream().anyMatch(List.of("return", "while", "else")::contains));
        List<String> statement = complete(SHAPES.replace("    val color = Color.RED", "    <caret>\n    val color = Color.RED"));
        assertTrue(statement.toString(), statement.containsAll(List.of("val", "return", "for")) && !statement.contains("package"));
        for (String value : List.of("val v = if (true) 1 else <caret>", "val v = try <caret>", "for (e in <caret>) {}")) {
            List<String> found = complete(SHAPES.replace("    val color = Color.RED", "    " + value + "\n    val color = Color.RED"));
            assertTrue(value + found, found.contains("if") && !found.contains("val") && !found.contains("while"));
        }
    }

    public void testCompletesMembersOfLambdaParametersLoopVariablesAndGenericResults() {
        String main = """
                package app

                import java.util.List

                data class Person(val name: String, val age: Int)

                fn main() {
                    val xs = List.of("a", "b")
                    val p = Person("a", 1)
                    %s
                }
                """;
        for (String line : List.of("xs |> map { it.<caret> }", "xs |> map { s -> s.<caret> }",
                "xs |> filter { it.isEmpty() } |> forEach { it.<caret> }", "for (e in xs) { println(e.<caret>) }",
                "println(xs.get(0).<caret>)")) {
            List<String> found = complete(main.formatted(line));
            assertTrue(line + found, found.contains("length"));
        }
        assertTrue(complete(main.formatted("println(List.of(p).get(0).<caret>)")).contains("age"));
        for (String line : List.of("xs.forEach({ println(it.<caret>) })", "xs.stream().map({ s -> s.<caret> })",
                "val zs = xs |> filter { it.isEmpty() }\n    println(zs.get(0).<caret>)")) {
            List<String> found = complete(main.formatted(line));
            assertTrue(line + found, found.contains("length"));
        }
        for (String line : List.of("val ys = xs |> map { it.length() }\n    println(ys.get(0).<caret>)",
                "xs |> map { it.length() } |> map { it.<caret> }")) {
            List<String> found = complete(main.formatted(line));
            assertTrue(line + found, found.contains("intValue"));
        }
        assertTrue(complete(main.formatted("attempt Integer.parseInt(\"1\") else e -> e.<caret>")).contains("getMessage"));
    }

    public void testOffersOnlyTypesWhereADeclarationNamesOne() {
        for (String type : List.of("    val z: <caret>\n    val color = Color.RED", "fn g(a: <caret>) = 1\nfn main() {",
                "fn h() -> <caret> = 1\nfn main() {")) {
            String text = type.startsWith(" ") ? SHAPES.replace("    val color = Color.RED", type) : SHAPES.replace("fn main() {", type);
            List<String> found = complete(text);
            assertTrue(type + found, found.containsAll(List.of("Circle", "Int", "Boolean", "Result", "String")));
            assertTrue(type + found, found.stream().noneMatch(List.of("main", "if", "println", "color")::contains));
        }
    }

    private List<String> complete(String text) {
        myFixture.configureByText("Keywords.axl", text);
        myFixture.completeBasic();
        List<String> found = myFixture.getLookupElementStrings();
        assertNotNull(found);
        return found;
    }

    public void testHighlightsTypeDeclarationsAndEnumConstants() {
        myFixture.configureByText("Shapes.axl", SHAPES);
        String text = myFixture.getFile().getText();
        List<String> types = highlighted(text, AxlSyntaxHighlighter.TYPE_DECLARATION);
        assertEquals(List.of("Shape", "Circle", "Square", "Color"), types);
        assertEquals(List.of("RED", "GREEN", "BLUE"), highlighted(text, AxlSyntaxHighlighter.ENUM_CONSTANT));
    }

    private List<String> highlighted(String text, TextAttributesKey key) {
        return myFixture.doHighlighting().stream().filter(h -> h.forcedTextAttributesKey == key)
                .map(h -> text.substring(h.getStartOffset(), h.getEndOffset())).toList();
    }

    private void assertCompletes(String text, String... names) {
        myFixture.configureByText("Complete.axl", text);
        myFixture.completeBasic();
        List<String> found = myFixture.getLookupElementStrings();
        assertNotNull(found);
        assertTrue(found.toString(), found.containsAll(List.of(names)));
    }

    /** What the reference at the `|` of a snippet of the current file resolves to. */
    private PsiElement resolveAt(String snippet) {
        String text = myFixture.getFile().getText();
        int start = text.indexOf(snippet.replace("|", ""));
        assertTrue(snippet, start >= 0);
        PsiReference reference = myFixture.getFile().findReferenceAt(start + snippet.indexOf('|'));
        assertNotNull(snippet, reference);
        PsiElement target = reference.resolve();
        assertNotNull(snippet, target);
        return target;
    }

    private PsiClass findClass(String name) {
        PsiClass cls = JavaPsiFacade.getInstance(getProject()).findClass(name, GlobalSearchScope.allScope(getProject()));
        assertNotNull(name, cls);
        return cls;
    }
}
