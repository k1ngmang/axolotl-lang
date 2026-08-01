package org.axl.intellij;

import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.openapi.editor.colors.TextAttributesKey;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiAnnotation;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiField;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiModifier;
import com.intellij.psi.PsiReference;
import com.intellij.psi.PsiType;
import com.intellij.psi.PsiTypeParameter;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.util.PsiUtil;
import com.intellij.testFramework.LightProjectDescriptor;
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import org.axl.intellij.highlight.AxlSyntaxHighlighter;
import org.axl.intellij.psi.AxlFile;
import org.axl.intellij.psi.AxlFunction;
import org.axl.intellij.psi.AxlTypeDeclaration;
import org.axl.intellij.psi.AxlVariable;
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

    static final String NOTES = """
            package app

            interface Notes {
                val name: String
                fn add(text: String) -> Int
                fn find(id: Int) -> String?
                fn clear()
            }

            class MemoryNotes(val name: String) : Notes {
                val items: java.util.List<String> = java.util.ArrayList<String>()

                fn add(text: String) -> Int {
                    items.add(text)
                    return items.size()
                }

                fn find(id: Int) -> String? = if (id >= 1 && id <= items.size()) items.get(id - 1) else null

                fn clear() {
                    items.clear()
                }
            }

            fn describe(notes: Notes) -> String {
                val id = notes.add("buy milk")
                notes.clear()
                return notes.name() + " " + id + " " + (notes.find(id) ?: "nothing")
            }
            """;

    static final String GENERICS = """
            package app

            import java.util.ArrayList
            import java.util.List

            data class Pair<A, B>(val first: A, val second: B)

            interface Repository<K, V> {
                fn find(key: K) -> V?
                fn save(key: K, value: V)
            }

            class Stack<T : Object> {
                val items: List<T> = ArrayList<T>()

                fn push(item: T) {
                    items.add(item)
                }

                fn pop() -> T? = if (items.isEmpty()) null else items.remove(items.size() - 1)
            }

            fn firstOrNull<T>(xs: List<T>) -> T? = if (xs.isEmpty()) null else xs.get(0)

            fn largest<T : Comparable<T>>(xs: List<T>) -> T? {
                var best: T? = null
                for (x in xs) {
                    if (best == null || x.compareTo(best) > 0) {
                        best = x
                    }
                }
                return best
            }

            fn swap<A, B>(p: Pair<A, B>) -> Pair<B, A> = Pair<B, A>(p.second(), p.first())

            fn main() {
                val pair = Pair<String, Int>("age", 41)
                println(pair.first().length())
                val stack = Stack<String>()
                stack.push("a")
                println(stack.pop())
                println(swap(pair).second())
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

    public void testJavaSeesTheMethodsOfAnInterfaceAsAbstractAndTheirImplementations() {
        myFixture.addFileToProject("app/Notes.axl", NOTES);
        PsiClass notes = findClass("app.Notes");
        assertTrue(notes.isInterface());
        assertEquals(List.of("add", "clear", "describe", "find", "name"),
                Arrays.stream(notes.getMethods()).map(PsiMethod::getName).sorted().toList());
        PsiMethod add = notes.findMethodsByName("add", false)[0];
        assertTrue(add.hasModifierProperty(PsiModifier.ABSTRACT));
        assertEquals("int", add.getReturnType().getPresentableText());
        assertEquals("String", add.getParameterList().getParameters()[0].getType().getPresentableText());
        assertEquals("text", add.getParameterList().getParameters()[0].getName());
        PsiMethod find = notes.findMethodsByName("find", false)[0];
        assertEquals("String", find.getReturnType().getPresentableText());
        assertTrue(find.getReturnType().getAnnotations().length > 0 || find.getModifierList().getAnnotations().length > 0);
        assertEquals("void", notes.findMethodsByName("clear", false)[0].getReturnType().getPresentableText());

        PsiClass memory = findClass("app.Notes.MemoryNotes");
        assertTrue(memory.isInheritor(notes, false));
        assertNotNull(memory.findMethodsByName("find", false)[0].getModifierList().findAnnotation("java.lang.Override"));
        myFixture.configureByText("UseFromJava.java", """
                package app;

                class UseFromJava implements Notes {
                    public String name() { return "j"; }
                    public int add(String text) { return 1; }
                    public String find(int id) { return null; }
                    public void clear() {}

                    int f(Notes notes) {
                        notes.clear();
                        return notes.add("x") + notes.find(1).length();
                    }
                }
                """);
        assertEquals(List.of(), myFixture.doHighlighting().stream()
                .filter(h -> h.getSeverity() == HighlightSeverity.ERROR).map(h -> h.getDescription()).toList());
    }

    public void testJavaSeesTheMethodsOfTheInterfaceThatIsTheTypeOfItsFile() {
        myFixture.addFileToProject("app/Store.axl", """
                package app

                interface Store {
                    fn get(key: String) -> String?
                    fn put(key: String, value: String)
                }

                fn describe(store: Store, key: String) -> String = key + "=" + (store.get(key) ?: "unset")
                """);
        PsiClass store = findClass("app.Store");
        assertTrue(store.isInterface());
        assertTrue(store.findMethodsByName("get", false)[0].hasModifierProperty(PsiModifier.ABSTRACT));
        assertTrue(store.findMethodsByName("describe", false)[0].hasModifierProperty(PsiModifier.STATIC));
        assertEquals(2, store.findMethodsByName("put", false)[0].getParameterList().getParametersCount());
    }

    public void testCopiesTheAnnotationsOfTheGeneratedClasses() {
        myFixture.addFileToProject("app/Marker.java", """
                package app;
                @java.lang.annotation.Target({java.lang.annotation.ElementType.METHOD, java.lang.annotation.ElementType.TYPE,
                        java.lang.annotation.ElementType.FIELD, java.lang.annotation.ElementType.PARAMETER,
                        java.lang.annotation.ElementType.RECORD_COMPONENT})
                public @interface Marker {
                    String value() default "";
                    int weight() default 0;
                    Class<?> type() default Object.class;
                }
                """);
        myFixture.addFileToProject("app/T.axl", "package app\n\nclass T { @Deprecated fn x() {} }\n");
        PsiMethod x = findClass("app.T").findMethodsByName("x", false)[0];
        assertTrue(x.getModifierList().getText(), x.getModifierList().hasAnnotation("java.lang.Deprecated"));
        assertEquals("java.lang.Deprecated", x.getModifierList().getAnnotations()[0].getQualifiedName());
        assertTrue(x.isDeprecated());

        myFixture.addFileToProject("app/Marked.axl", """
                package app

                @Marker(value = "class", weight = 2, type = Int.class)
                class Counter(@Marker("x") val start: Int) {
                    @Marker("prop")
                    var count: Int = start

                    @Marker(weight = -1)
                    fn next(@Marker("p") step: Int) -> Int = count + step
                }

                @Marker("record")
                data class Point(@Marker("x") val x: Int, val y: Int)
                """);
        PsiClass counter = findClass("app.Marked.Counter");
        PsiAnnotation onClass = counter.getModifierList().findAnnotation("app.Marker");
        assertNotNull(counter.getModifierList().getText(), onClass);
        assertEquals("\"class\"", onClass.findAttributeValue("value").getText());
        assertEquals("2", onClass.findAttributeValue("weight").getText());
        assertEquals("Integer.class", onClass.findAttributeValue("type").getText());
        PsiMethod next = counter.findMethodsByName("next", false)[0];
        assertEquals("-1", next.getModifierList().findAnnotation("app.Marker").findAttributeValue("weight").getText());
        assertEquals("\"p\"", next.getParameterList().getParameters()[0].getModifierList().findAnnotation("app.Marker")
                .findAttributeValue("value").getText());
        assertEquals("\"prop\"", counter.findFieldByName("count", false).getModifierList().findAnnotation("app.Marker")
                .findAttributeValue("value").getText());
        assertEquals("\"x\"", counter.findFieldByName("start", false).getModifierList().findAnnotation("app.Marker")
                .findAttributeValue("value").getText());
        PsiClass point = findClass("app.Marked.Point");
        assertEquals("\"record\"", point.getModifierList().findAnnotation("app.Marker").findAttributeValue("value").getText());
        assertEquals("\"x\"", point.findFieldByName("x", false).getModifierList().findAnnotation("app.Marker")
                .findAttributeValue("value").getText());
        assertNotNull(findClass("app.Marked").getModifierList().findAnnotation("org.jspecify.annotations.NullMarked"));
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

    public void testInterfaceMethodsNavigateToTheirDeclarationsAndResolve() {
        AxlFile file = (AxlFile) myFixture.configureByText("Notes.axl", NOTES);
        AxlTypeDeclaration notes = file.typeDeclarations().getFirst();
        AxlTypeDeclaration memory = file.typeDeclarations().get(1);
        PsiClass light = findClass("app.Notes");
        assertSame(notes.methods().getFirst(), light.findMethodsByName("add", false)[0].getNavigationElement());
        assertSame(notes.methods().get(2), light.findMethodsByName("clear", false)[0].getNavigationElement());
        assertSame(notes.fields().getFirst(), light.findMethodsByName("name", false)[0].getNavigationElement());
        assertSame(notes.methods().getFirst(), resolveAt("val id = notes.|add(\"buy milk\")"));
        assertSame(notes.methods().get(2), resolveAt("notes.|clear()"));
        assertSame(notes.methods().get(1), resolveAt("(notes.|find(id)"));
        assertSame(notes.fields().getFirst(), resolveAt("return notes.|name()"));
        assertSame(notes.methods().getFirst(), org.axl.intellij.java.AxlLightClasses.methodOf(notes.methods().getFirst()).getNavigationElement());
        assertEquals("add", memory.methods().getFirst().getName());
    }

    public void testShowsNoErrorsForInterfaceMethodsElvisThrowAndAttemptStatements() {
        for (String name : List.of("interface_methods", "elvis_throw", "attempt_statement")) {
            myFixture.configureByText("Main.axl", read(Path.of("..", "tests", "conformance", name, "src", "app", "Main.axl")));
            assertEquals(name, List.of(), myFixture.doHighlighting().stream()
                    .filter(h -> h.getSeverity() == HighlightSeverity.ERROR).map(h -> h.getDescription()).toList());
        }
    }

    public void testShowsTheErrorsOfInterfaceMethods() {
        myFixture.configureByText("Main.axl", read(Path.of("..", "tests", "conformance", "interface_method_errors", "src", "app", "Main.axl")));
        List<String> errors = myFixture.doHighlighting().stream().filter(h -> h.getSeverity() == HighlightSeverity.ERROR)
                .map(h -> h.getDescription()).toList();
        assertTrue(errors.toString(), errors.stream().anyMatch(e -> e.contains("requires `fn area() -> Double`")));
        assertTrue(errors.toString(), errors.stream().anyMatch(e -> e.contains("is declared twice")));
    }

    public void testFindsAndRenamesInterfaceMethods() {
        AxlFile file = (AxlFile) myFixture.configureByText("Notes.axl", NOTES);
        AxlTypeDeclaration notes = file.typeDeclarations().getFirst();
        assertEquals(1, myFixture.findUsages(notes.methods().getFirst()).size());
        assertEquals(1, myFixture.findUsages(notes.methods().get(2)).size());
        myFixture.renameElement(notes.methods().get(1), "lookup");
        String text = myFixture.getFile().getText();
        assertTrue(text, text.contains("fn lookup(id: Int) -> String?") && text.contains("notes.lookup(id)"));
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

    public void testCompletesTheMethodsOfAnInterfaceTypedValue() {
        assertCompletes(NOTES.replace("notes.clear()", "notes.<caret>"), "add", "find", "clear", "name");
    }

    public void testTypesAnElvisThrowAsTheTypeOfItsLeftSide() {
        String main = """
                package app

                import java.util.Map

                fn lookup(m: Map<String, String>, key: String, s: String?) -> Int {
                    val text = s ?: throw IllegalStateException("missing")
                    val value = m.get(key) ?: m.get("other") ?: throw IllegalArgumentException("none " + key)
                    return text.length() + value.length() + (s ?: throw IllegalStateException("x")).length()
                }
                """;
        myFixture.configureByText("Lookup.axl", main);
        assertEquals("length", ((PsiMethod) resolveAt("return text.|length()")).getName());
        assertEquals("java.lang.String", ((PsiMethod) resolveAt("return text.|length()")).getContainingClass().getQualifiedName());
        assertEquals("java.lang.String", ((PsiMethod) resolveAt("+ value.|length()")).getContainingClass().getQualifiedName());
        assertEquals("java.lang.String", ((PsiMethod) resolveAt("(s ?: throw IllegalStateException(\"x\")).|length()")).getContainingClass().getQualifiedName());
        assertCompletes(main.replace("return text.length()", "return text.<caret>"), "length", "isEmpty", "substring");
        assertCompletes(main.replace("+ value.length()", "+ value.<caret>"), "length", "trim");
    }

    public void testCompletesTheMembersOfAResultFromTheRuntimeClass() {
        String main = read(Path.of("..", "tests", "conformance", "result_methods", "src", "app", "Main.axl"));
        myFixture.configureByText("Main.axl", main);
        assertEquals("axl.lang.Result", ((PsiMethod) resolveAt("good.|isOk()")).getContainingClass().getQualifiedName());
        assertEquals("axl.lang.Result", ((PsiMethod) resolveAt("good.|map({ n -> n + 1 })")).getContainingClass().getQualifiedName());
        assertEquals("axl.lang.Result", ((PsiMethod) resolveAt("bad.|mapErr(")).getContainingClass().getQualifiedName());
        assertEquals("axl.lang.Result", ((PsiMethod) resolveAt("map({ n -> n + 1 }).|getOrElse(0)")).getContainingClass().getQualifiedName());
        assertCompletes(main.replace("println(good.isOk())", "good.<caret>"), "isOk", "isErr", "map", "getOrElse", "mapErr");
        assertCompletes(main.replace("println(bad.isErr())", "bad.map({ n -> n }).<caret>"), "isOk", "isErr", "map", "getOrElse", "mapErr");
    }

    public void testKeepsTheMembersOfTypesWhileTheFileHasErrors() {
        for (String error : List.of("fn g() = \"x\"\n\n", "fn g() -> Int = 1 +\n\n", "fn g(a: Int) {\n    val a = 2\n}\n\n")) {
            String text = SHAPES.replace("fn main() {", error + "fn main() {");
            myFixture.configureByText("Shapes.axl", text);
            assertTrue(error, resolveAt("println(c.rad|ius())") instanceof AxlVariable);
            assertCompletes(text.replace("println(c.radius())", "println(c.<caret>)"), "radius", "name");
        }
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

    public void testOffersAnnotationTypesAfterAnAt() {
        List<String> found = complete(SHAPES.replace("package app\n", "package app\nimport org.jspecify.annotations.NullMarked\nimport java.util.List\n")
                .replace("fn main() {", "@<caret>\nfn main() {"));
        assertTrue(found.toString(), found.containsAll(List.of("Deprecated", "Override", "NullMarked")));
        assertTrue(found.toString(), found.stream().noneMatch(List.of("List", "fn", "Circle", "println", "if")::contains));
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


    public void testJavaSeesTheTypeParametersOfTypesAndFunctions() {
        myFixture.addFileToProject("app/Generics.axl", GENERICS);
        PsiClass pair = findClass("app.Generics.Pair");
        assertEquals(List.of("A", "B"), Arrays.stream(pair.getTypeParameters()).map(PsiTypeParameter::getName).toList());
        assertEquals("A", pair.findMethodsByName("first", false)[0].getReturnType().getPresentableText());
        assertEquals("B", pair.findFieldByName("second", false).getType().getPresentableText());
        PsiType component = pair.getConstructors()[0].getParameterList().getParameters()[0].getType();
        assertSame(pair.getTypeParameters()[0], PsiUtil.resolveClassInType(component));
        PsiClass stack = findClass("app.Generics.Stack");
        assertEquals(0, stack.getTypeParameters()[0].getExtendsListTypes().length);
        assertEquals("List<T>", stack.findFieldByName("items", false).getType().getPresentableText());
        assertSame(stack.getTypeParameters()[0], PsiUtil.resolveClassInType(stack.findMethodsByName("pop", false)[0].getReturnType()));
        assertEquals(List.of("K", "V"), Arrays.stream(findClass("app.Generics.Repository").getTypeParameters())
                .map(PsiTypeParameter::getName).toList());
        PsiMethod largest = findClass("app.Generics").findMethodsByName("largest", false)[0];
        assertEquals("Comparable<T>", largest.getTypeParameters()[0].getExtendsListTypes()[0].getPresentableText());
        assertEquals("List<T>", largest.getParameterList().getParameters()[0].getType().getPresentableText());
        assertSame(largest.getTypeParameters()[0], PsiUtil.resolveClassInType(largest.getReturnType()));
        PsiMethod firstOrNull = findClass("app.Generics").findMethodsByName("firstOrNull", false)[0];
        assertEquals("Object", firstOrNull.getTypeParameters()[0].getExtendsListTypes()[0].getPresentableText());
        myFixture.configureByText("UseFromJava.java", """
                package app;

                import java.util.List;

                class UseFromJava {
                    String f() {
                        Generics.Pair<String, Integer> p = new Generics.Pair<>("a", 1);
                        String first = p.first();
                        int second = p.second();
                        Generics.Stack<String> stack = new Generics.Stack<>();
                        stack.push("x");
                        String top = stack.pop();
                        Integer n = Generics.firstOrNull(List.of(1, 2));
                        String biggest = Generics.largest(List.of("a", "b"));
                        Generics.Pair<Integer, String> swapped = Generics.swap(p);
                        return first + second + top + n + biggest + swapped.first();
                    }
                }
                """);
        assertEquals(List.of(), myFixture.doHighlighting().stream()
                .filter(h -> h.getSeverity() == HighlightSeverity.ERROR).map(h -> h.getDescription()).toList());
    }

    public void testTypesGenericConstructorsAndTheValuesOfTypeParameters() {
        myFixture.configureByText("Generics.axl", GENERICS);
        assertEquals("java.lang.String", ((PsiMethod) resolveAt("println(pair.first().|length())")).getContainingClass().getQualifiedName());
        assertEquals("java.lang.Comparable", ((PsiMethod) resolveAt("x.|compareTo(best)")).getContainingClass().getQualifiedName());
        assertEquals("java.util.List", ((PsiMethod) resolveAt("items.|add(item)")).getContainingClass().getQualifiedName());
        assertCompletes(GENERICS.replace("val pair = Pair<String, Int>(\"age\", 41)", "val pair = Pair<String, Int>(\"age\", 41).<caret>"),
                "first", "second");
        assertCompletes(GENERICS.replace("println(pair.first().length())", "println(pair.first().<caret>)"), "length", "isEmpty");
        assertCompletes(GENERICS.replace("println(stack.pop())", "println(stack.pop().<caret>)"), "length", "trim");
        assertCompletes(GENERICS.replace("println(swap(pair).second())", "println(swap(pair).second().<caret>)"), "length");
        assertCompletes(GENERICS.replace("stack.push(\"a\")", "stack.<caret>"), "push", "pop", "items");
        assertCompletes(GENERICS.replace("items.add(item)", "item.<caret>"), "toString", "hashCode", "equals");
        assertCompletes(GENERICS.replace("x.compareTo(best) > 0", "x.<caret>"), "compareTo", "toString");
    }

    public void testResolvesTypeParametersInsideTheirDeclarations() {
        AxlFile file = (AxlFile) myFixture.configureByText("Generics.axl", GENERICS);
        AxlTypeDeclaration pair = file.typeDeclarations().getFirst();
        AxlTypeDeclaration stack = file.typeDeclarations().get(2);
        AxlFunction largest = file.functions().get(1);
        assertSame(pair.typeParameters().get(1), resolveAt("val second: |B)"));
        assertSame(stack.typeParameters().getFirst(), resolveAt("fn push(item: |T)"));
        assertSame(stack.typeParameters().getFirst(), resolveAt("ArrayList<|T>()"));
        assertSame(largest.typeParameters().getFirst(), resolveAt("fn largest<T : Comparable<|T>>"));
        assertSame(largest.typeParameters().getFirst(), resolveAt("var best: |T? = null"));
        assertSame(file.functions().get(2).typeParameters().getFirst(), resolveAt("= Pair<B, |A>(p.second()"));
        assertEquals(List.of(), myFixture.doHighlighting().stream()
                .filter(h -> h.getSeverity() == HighlightSeverity.ERROR).map(h -> h.getDescription()).toList());
    }

    public void testCompletesRenamesAndFindsTypeParameters() {
        List<String> found = complete(GENERICS.replace("fn push(item: T)", "fn push(item: <caret>)"));
        assertTrue(found.toString(), found.containsAll(List.of("T", "Stack", "Pair", "String")) && !found.contains("items"));
        found = complete(GENERICS.replace("var best: T? = null", "var best: <caret>"));
        assertTrue(found.toString(), found.contains("T"));
        AxlFile file = (AxlFile) myFixture.configureByText("Generics.axl", GENERICS);
        assertEquals(4, myFixture.findUsages(file.functions().get(1).typeParameters().getFirst()).size());
        myFixture.renameElement(file.typeDeclarations().get(2).typeParameters().getFirst(), "E");
        String text = myFixture.getFile().getText();
        assertTrue(text, text.contains("class Stack<E : Object> {") && text.contains("val items: List<E> = ArrayList<E>()"));
        assertTrue(text, text.contains("fn push(item: E)") && text.contains("fn pop() -> E?"));
        assertTrue(text, text.contains("fn largest<T : Comparable<T>>(xs: List<T>) -> T? {") && text.contains("var best: T? = null"));
    }

    public void testHighlightsTypeParametersWhereTheyAreDeclaredAndUsed() {
        myFixture.configureByText("Generic.axl", "package app\n\nfn firstOrNull<T>(xs: java.util.List<T>) -> T? = "
                + "if (xs.isEmpty()) null else xs.get(0)\n\ndata class Pair<A, B>(val first: A, val second: B)\n");
        String text = myFixture.getFile().getText();
        assertEquals(List.of("T", "T", "T", "A", "B", "A", "B"), highlighted(text, AxlSyntaxHighlighter.TYPE_PARAMETER));
        assertEquals(List.of("Pair"), highlighted(text, AxlSyntaxHighlighter.TYPE_DECLARATION));
    }

    public void testHighlightsAnnotationsWithoutTheirArguments() {
        myFixture.configureByText("Marked.axl", COUNTER.replace("class Counter", "@Deprecated\n@java.lang.SuppressWarnings({\"unused\"})\nclass Counter")
                .replace("    fn next()", "    @Override fn toString() -> String = name\n    fn next()"));
        String text = myFixture.getFile().getText();
        assertEquals(List.of("@Deprecated", "@java.lang.SuppressWarnings", "@Override"), highlighted(text, AxlSyntaxHighlighter.ANNOTATION));
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
