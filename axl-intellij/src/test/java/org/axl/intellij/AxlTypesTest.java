package org.axl.intellij;

import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.openapi.editor.colors.TextAttributesKey;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiAnnotation;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiErrorElement;
import com.intellij.psi.PsiField;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiModifier;
import com.intellij.psi.PsiReference;
import com.intellij.psi.PsiType;
import com.intellij.psi.PsiTypeParameter;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.util.PsiUtil;
import com.intellij.testFramework.LightProjectDescriptor;
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import org.axl.compiler.Translator;
import org.axl.intellij.highlight.AxlSyntaxHighlighter;
import org.axl.intellij.java.TranslationOptions;
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

    /** An enum with fields, classes extending Java classes, a data class with methods and a `use` statement. */
    static final String EXTENDED = """
            package app

            enum Planet(val mass: Double, val radius: Double) {
                MERCURY(3.303e23, 2.4397e6),
                EARTH(5.976e24, 6.37814e6)
            }

            class NotFound(val id: String) : RuntimeException("not found: " + id)

            class Loud(val times: Int) : Thread("loud") {
                var shouted = 0

                fn run() {
                    shouted += times
                }
            }

            data class Money(val cents: Long) : Comparable<Money> {
                fn compareTo(other: Money) -> Int = Long.compare(cents, other.cents())

                fn plus(other: Money) -> Money = Money(cents + other.cents())

                fn show() -> String = cents + ""
            }

            fn gravity(p: Planet) -> Double = 6.67300E-11 * p.mass / (p.radius * p.radius)

            fn main() {
                println(Planet.EARTH.mass)
                val loud = Loud(3)
                loud.start()
                println(loud.shouted + " " + loud.getName())
                val missing = NotFound("7")
                println(missing.getMessage() + missing.id)
                println(Money(250).plus(Money(99)).show())
                use (val reader = java.io.StringReader("x")) {
                    println(reader.read())
                } else e -> println(e.getMessage())
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

    public void testReadsTheDeclarationsOfTheOtherFilesOfTheModule() {
        myFixture.addFileToProject("app/Types.axl", """
                package app

                interface Greeter {
                    val name: String
                }

                sealed interface Option<T>
                data class Some<T>(val value: T) : Option<T>
                """);
        AxlFile terse = (AxlFile) myFixture.addFileToProject("app/Terse.axl", """
                package app

                class Terse : Greeter {
                    val name: String = "t"
                }

                fn f(o: Option<String>) -> Int = if (o is Some) o.value().length() else 0
                """);
        PsiMethod[] name = findClass("app.Terse").findMethodsByName("name", false);
        assertEquals(1, name.length);
        assertEquals("String", name[0].getReturnType().getPresentableText());
        assertTrue(name[0].hasModifierProperty(PsiModifier.PUBLIC));
        String java = Translator.translate(terse.sourcePath(), terse.getName(), terse.getText(), TranslationOptions.of(terse)).unit().javaSource();
        assertTrue(java, java.contains("o instanceof Some<String>"));
        myFixture.configureFromExistingVirtualFile(terse.getVirtualFile());
        assertEquals(List.of(), myFixture.doHighlighting().stream()
                .filter(h -> h.getSeverity() == HighlightSeverity.ERROR).map(h -> h.getDescription()).toList());
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

    public void testShowsTheErrorsOfJumpsAndAssignmentsInAWhenThatGivesAValue() {
        myFixture.configureByText("Main.axl", read(Path.of("..", "tests", "conformance", "when_arm_statement_errors", "src", "app", "Main.axl")));
        List<String> errors = myFixture.doHighlighting().stream().filter(h -> h.getSeverity() == HighlightSeverity.ERROR)
                .map(h -> h.getDescription()).toList();
        assertEquals(errors.toString(), 4, errors.size());
        assertTrue(errors.toString(), errors.stream().anyMatch(e -> e.contains("`break` cannot leave a `when` that gives a value")));
        assertTrue(errors.toString(), errors.stream().anyMatch(e -> e.contains("`continue` cannot leave a `when` that gives a value")));
        assertTrue(errors.toString(), errors.stream().anyMatch(e -> e.contains("each branch of a `when` that gives a value ends with the value")));
        assertTrue(errors.toString(), errors.stream().anyMatch(e -> e.contains("`return` cannot leave a `when` that gives a value")));
    }

    public void testNarrowsAndResolvesInTheAssignmentArmsOfAWhenStatement() {
        AxlFile shapes = (AxlFile) myFixture.configureByText("Shapes.axl", SHAPES.replace("fn main() {", """
                fn total(shapes: java.util.List<Shape>) -> Double {
                    var total = 0.0
                    for (shape in shapes) {
                        when (shape) {
                            is Circle -> total += shape.radius()
                            is Square -> total = shape.side()
                            else -> continue
                        }
                    }
                    return total
                }

                fn main() {"""));
        assertSame(shapes.typeDeclarations().get(1).fields().get(1), resolveAt("total += shape.|radius()"));
        assertSame(shapes.typeDeclarations().get(2).fields().get(1), resolveAt("total = shape.|side()"));
        assertEquals(List.of(), myFixture.doHighlighting().stream()
                .filter(h -> h.getSeverity() == HighlightSeverity.ERROR).map(h -> h.getDescription()).toList());
    }

    public void testResolvesTheErrorInTheExitOfAnAttemptStatement() {
        myFixture.configureByText("Main.axl", """
                package app

                fn send(i: Int) {
                    println(i)
                }

                fn describe(i: Int) -> String {
                    attempt send(i) else e -> return "failed: " + e.getMessage()
                    return "sent"
                }
                """);
        PsiElement error = resolveAt("\"failed: \" + |e.getMessage()");
        assertTrue(error.toString(), error instanceof AxlVariable variable && variable.getName().equals("e"));
        assertEquals(error.getTextOffset(), myFixture.getFile().getText().indexOf("e -> return"));
        assertEquals(List.of(), myFixture.doHighlighting().stream()
                .filter(h -> h.getSeverity() == HighlightSeverity.ERROR).map(h -> h.getDescription()).toList());
    }

    public void testResolvesConstructorParametersAndHighlightsConstructor() {
        myFixture.configureByText("Main.axl", """
                package app

                class Opt(val opt: String?, val hasArg: Boolean) {
                    constructor(opt: String?) = this(opt, false)

                    @Deprecated
                    private constructor() = this(null, true)

                    fn describe() -> String = opt + hasArg
                }

                fn main() {
                    println(Opt("a").describe())
                }
                """);
        PsiElement parameter = resolveAt("this(|opt, false)");
        assertTrue(parameter.toString(), parameter instanceof AxlVariable variable && variable.getName().equals("opt"));
        assertEquals(myFixture.getFile().getText().indexOf("opt: String?) ="), parameter.getTextOffset());
        String text = myFixture.getFile().getText();
        assertEquals(List.of("constructor", "constructor"), highlighted(text, AxlSyntaxHighlighter.KEYWORD));
        assertEquals(List.of(), myFixture.doHighlighting().stream()
                .filter(h -> h.getSeverity() == HighlightSeverity.ERROR).map(h -> h.getDescription()).toList());
        assertTrue(complete(text.replace("    fn describe()", "    <caret>\n    fn describe()")).contains("constructor"));
        PsiClass opt = findClass("app.Main.Opt");
        PsiMethod[] constructors = opt.getConstructors();
        assertEquals(3, constructors.length);
        assertEquals(text.indexOf("constructor(opt"), constructors[1].getNavigationElement().getTextOffset());
        assertTrue(constructors[2].hasModifierProperty(PsiModifier.PRIVATE));
    }

    public void testReadsThePrivateAndAnnotatedConstructorOfAClassHeader() {
        myFixture.configureByText("Main.axl", """
                package app

                class Parser private constructor(private val partial: Boolean, val quotes: Boolean?) {
                    constructor() = this(true, null)

                    fn describe() -> String = "p" + partial + quotes
                }

                class Builder @Deprecated(since = "2") constructor() {
                    var count = 0
                }

                fn main() {
                    println(Parser().describe())
                }
                """);
        String text = myFixture.getFile().getText();
        assertNull(PsiTreeUtil.findChildOfType(myFixture.getFile(), PsiErrorElement.class));
        assertEquals(List.of("constructor", "constructor", "constructor"), highlighted(text, AxlSyntaxHighlighter.KEYWORD));
        assertEquals(List.of(), myFixture.doHighlighting().stream()
                .filter(h -> h.getSeverity() == HighlightSeverity.ERROR).map(h -> h.getDescription()).toList());
        assertTrue(complete(text.replace("\"p\" + partial", "\"p\" + <caret>partial")).contains("partial"));
        PsiClass parser = findClass("app.Main.Parser");
        assertFalse(parser.hasModifierProperty(PsiModifier.PRIVATE));
        assertTrue(parser.getConstructors()[0].hasModifierProperty(PsiModifier.PRIVATE));
        assertFalse(parser.getConstructors()[1].hasModifierProperty(PsiModifier.PRIVATE));
        assertTrue(findClass("app.Main.Builder").getConstructors()[0].isDeprecated());
    }

    public void testShowsTheErrorsOfTheExitsOfAnAttemptStatement() {
        myFixture.configureByText("Main.axl", read(Path.of("..", "tests", "conformance", "attempt_exit_errors", "src", "app", "Main.axl")));
        List<String> errors = myFixture.doHighlighting().stream().filter(h -> h.getSeverity() == HighlightSeverity.ERROR)
                .map(h -> h.getDescription()).toList();
        assertEquals(errors.toString(), 5, errors.size());
        assertTrue(errors.toString(), errors.stream().anyMatch(e -> e.contains("`break` works only inside a `while` or `for` loop")));
        assertTrue(errors.toString(), errors.stream().anyMatch(e -> e.contains("`return` cannot leave a `when` that gives a value")));
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

    public void testDoesNotCompleteThePrivateParametersOfAClassOutsideIt() {
        List<String> found = complete("""
                package app

                class Opt(val name: String, private val description: String?) {
                    fn same(other: Opt) -> Boolean = java.util.Objects.equals(other.description, description)
                }

                fn main() {
                    println(Opt("a", "b").<caret>)
                }
                """);
        assertTrue(found.toString(), found.contains("name") && !found.contains("description"));
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
        assertTrue(complete(main.formatted("attempt java.nio.file.Files.delete(java.nio.file.Path.of(\"a\")) "
                + "else e: java.nio.file.FileSystemException -> println(e.<caret>)")).contains("getFile"));
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

    public void testHighlightsEnumConstantsButNotEnumFieldsAndUseOnlyBeforeItsResource() {
        myFixture.configureByText("Extended.axl", EXTENDED.replace("println(Planet.EARTH.mass)", "val use = 1\n    println(Planet.EARTH.mass + use)"));
        String text = myFixture.getFile().getText();
        assertEquals(List.of("MERCURY", "EARTH"), highlighted(text, AxlSyntaxHighlighter.ENUM_CONSTANT));
        assertEquals(List.of("data", "use"), highlighted(text, AxlSyntaxHighlighter.KEYWORD));
        assertEquals(List.of(), myFixture.doHighlighting().stream()
                .filter(h -> h.getSeverity() == HighlightSeverity.ERROR).map(h -> h.getDescription()).toList());
    }

    public void testShowsNoErrorsForTheConformanceCasesOfTheNewStatementsAndMembers() {
        for (String name : List.of("loop_jumps", "use_resources", "enum_fields", "java_superclass", "data_class_methods",
                "attempt_in_lambda", "property_inference", "result_else_narrowing", "pipe_sorted_comparator", "console_io",
                "when_arm_statements", "attempt_exits", "attempt_typed_handler", "class_constructors", "primary_constructors")) {
            myFixture.configureByText("Main.axl", read(Path.of("..", "tests", "conformance", name, "src", "app", "Main.axl")));
            assertEquals(name, List.of(), myFixture.doHighlighting().stream()
                    .filter(h -> h.getSeverity() == HighlightSeverity.ERROR).map(h -> h.getDescription()).toList());
        }
    }

    public void testJavaSeesEnumFieldsJavaSuperclassesAndDataClassMethods() {
        myFixture.addFileToProject("app/Extended.axl", EXTENDED);
        PsiClass planet = findClass("app.Extended.Planet");
        assertTrue(planet.isEnum());
        assertEquals(List.of("MERCURY", "EARTH", "mass", "radius"), Arrays.stream(planet.getFields()).map(PsiField::getName).toList());
        PsiField mass = planet.findFieldByName("mass", false);
        assertEquals("double", mass.getType().getPresentableText());
        assertFalse(mass.hasModifierProperty(PsiModifier.STATIC));
        assertTrue(mass.hasModifierProperty(PsiModifier.FINAL));
        assertEquals(1, planet.getConstructors().length);
        assertEquals(2, planet.getConstructors()[0].getParameterList().getParametersCount());

        PsiClass notFound = findClass("app.Extended.NotFound");
        assertEquals("java.lang.RuntimeException", notFound.getSuperClass().getQualifiedName());
        assertEquals(0, notFound.getImplementsListTypes().length);
        PsiClass loud = findClass("app.Extended.Loud");
        assertTrue(loud.isInheritor(findClass("java.lang.Thread"), true));
        assertEquals("int", loud.findFieldByName("shouted", false).getType().getPresentableText());
        assertEquals("void", loud.findMethodsByName("run", false)[0].getReturnType().getPresentableText());

        PsiClass money = findClass("app.Extended.Money");
        assertEquals("Comparable<Money>", money.getImplementsListTypes()[0].getPresentableText());
        assertEquals("Money", money.findMethodsByName("plus", false)[0].getReturnType().getPresentableText());
        assertEquals("long", money.findMethodsByName("cents", false)[0].getReturnType().getPresentableText());
        myFixture.configureByText("UseFromJava.java", """
                package app;

                class UseFromJava {
                    String f() throws Exception {
                        double m = Extended.Planet.EARTH.mass + Extended.Planet.MERCURY.radius;
                        Extended.Loud loud = new Extended.Loud(3);
                        loud.start();
                        loud.join();
                        RuntimeException e = new Extended.NotFound("x");
                        Extended.Money money = new Extended.Money(1).plus(new Extended.Money(2));
                        return e.getMessage() + money.show() + money.compareTo(money) + money.cents() + m + loud.shouted;
                    }
                }
                """);
        assertEquals(List.of(), myFixture.doHighlighting().stream()
                .filter(h -> h.getSeverity() == HighlightSeverity.ERROR).map(h -> h.getDescription()).toList());
    }

    public void testEnumFieldsAndDataClassMethodsNavigateToTheirDeclarations() {
        AxlFile file = (AxlFile) myFixture.addFileToProject("app/Extended.axl", EXTENDED);
        AxlTypeDeclaration planet = file.typeDeclarations().getFirst();
        PsiClass light = findClass("app.Extended.Planet");
        assertSame(planet.fields().getFirst(), light.findFieldByName("mass", false).getNavigationElement());
        assertSame(planet.fields().get(2), light.findFieldByName("MERCURY", false).getNavigationElement());
        assertSame(planet, light.getConstructors()[0].getNavigationElement());
        AxlTypeDeclaration loud = file.typeDeclarations().get(2);
        assertSame(loud.methods().getFirst(), findClass("app.Extended.Loud").findMethodsByName("run", false)[0].getNavigationElement());
        assertSame(loud.fields().get(1), findClass("app.Extended.Loud").findFieldByName("shouted", false).getNavigationElement());
        AxlTypeDeclaration money = file.typeDeclarations().get(3);
        PsiClass record = findClass("app.Extended.Money");
        assertSame(money.methods().get(2), record.findMethodsByName("show", false)[0].getNavigationElement());
        assertSame(money.methods().getFirst(), record.findMethodsByName("compareTo", false)[0].getNavigationElement());
        assertSame(money.fields().getFirst(), record.findMethodsByName("cents", false)[0].getNavigationElement());
    }

    public void testResolvesEnumFieldsInheritedMembersDataClassMethodsAndUseLocals() {
        AxlFile file = (AxlFile) myFixture.configureByText("Extended.axl", EXTENDED);
        AxlTypeDeclaration planet = file.typeDeclarations().getFirst();
        assertSame(planet.fields().getFirst(), resolveAt("println(Planet.EARTH.|mass)"));
        assertSame(planet.fields().get(3), resolveAt("println(Planet.|EARTH.mass)"));
        assertSame(planet.fields().getFirst(), resolveAt("* p.|mass /"));
        assertSame(planet.fields().get(1), resolveAt("(p.|radius *"));
        assertEquals("java.lang.Thread", ((PsiMethod) resolveAt("loud.|start()")).getContainingClass().getQualifiedName());
        assertEquals("java.lang.Thread", ((PsiMethod) resolveAt("loud.|getName()")).getContainingClass().getQualifiedName());
        assertSame(file.typeDeclarations().get(2).fields().get(1), resolveAt("println(loud.|shouted"));
        assertEquals("java.lang.Throwable", ((PsiMethod) resolveAt("missing.|getMessage()")).getContainingClass().getQualifiedName());
        assertSame(file.typeDeclarations().get(1).fields().getFirst(), resolveAt("missing.|id)"));
        AxlTypeDeclaration money = file.typeDeclarations().get(3);
        assertSame(money.methods().get(1), resolveAt("Money(250).|plus(Money(99))"));
        assertSame(money.methods().get(2), resolveAt("plus(Money(99)).|show()"));
        assertSame(money.fields().getFirst(), resolveAt("Long.compare(|cents, other.cents())"));
        assertSame(money.fields().getFirst(), resolveAt("other.|cents())"));
        assertSame(money.fields().getFirst(), resolveAt("= Money(|cents + other.cents())"));
        PsiElement reader = resolveAt("println(|reader.read())");
        assertEquals("reader", ((AxlVariable) reader).getName());
        assertEquals(org.axl.intellij.parser.AxlElementTypes.VARIABLE, reader.getNode().getElementType());
        assertEquals("java.io.StringReader", ((PsiMethod) resolveAt("println(reader.|read())")).getContainingClass().getQualifiedName());
        PsiElement error = resolveAt("println(|e.getMessage())");
        assertEquals(org.axl.intellij.parser.AxlElementTypes.LAMBDA_PARAMETER, error.getNode().getElementType());
        assertEquals("java.lang.Throwable", ((PsiMethod) resolveAt("println(e.|getMessage())")).getContainingClass().getQualifiedName());
    }

    public void testNarrowsAfterAnIfThatLeavesTheLoopAndFoldsTheBodyOfADataClass() {
        AxlFile file = (AxlFile) myFixture.configureByText("Loop.axl", SHAPES + """

                fn radii(shapes: java.util.List<Shape>) -> Double {
                    var total = 0.0
                    for (shape in shapes) {
                        if (shape !is Circle) {
                            continue
                        }
                        total += shape.radius()
                    }
                    return total
                }
                """ + EXTENDED.substring(EXTENDED.indexOf("data class Money"), EXTENDED.indexOf("fn gravity")));
        assertSame(file.typeDeclarations().get(1).fields().get(1), resolveAt("total += shape.|radius()"));
        var document = myFixture.getEditor().getDocument();
        List<String> folded = Arrays.stream(new org.axl.intellij.highlight.AxlFoldingBuilder().buildFoldRegions(file, document, false))
                .map(d -> document.getText(d.getRange())).toList();
        assertTrue(folded.toString(), folded.stream().anyMatch(text -> text.startsWith("{\n    fn compareTo(other: Money)")));
    }

    public void testSeesTheResourceOfAUseOnlyInItsBlockAndTheErrorNameOnlyInItsHandler() {
        myFixture.configureByText("Scope.axl", EXTENDED.replace("} else e -> println(e.getMessage())",
                "} else e -> println(reader)\n    println(e)"));
        assertNull(myFixture.getFile().findReferenceAt(myFixture.getFile().getText().indexOf("println(reader)") + 8).resolve());
        assertNull(myFixture.getFile().findReferenceAt(myFixture.getFile().getText().indexOf("println(e)") + 8).resolve());
    }

    public void testCompletesEnumFieldsInheritedMembersDataClassMethodsAndUseLocals() {
        assertCompletes(EXTENDED.replace("println(Planet.EARTH.mass)", "println(Planet.EARTH.<caret>)"), "mass", "radius", "ordinal");
        assertCompletes(EXTENDED.replace("println(Planet.EARTH.mass)", "println(Planet.<caret>)"), "MERCURY", "EARTH");
        assertCompletes(EXTENDED.replace("loud.start()", "loud.<caret>"), "start", "shouted", "times", "getName", "run");
        assertCompletes(EXTENDED.replace("println(missing.getMessage() + missing.id)", "println(missing.<caret>)"), "getMessage", "id");
        assertCompletes(EXTENDED.replace("println(Money(250).plus(Money(99)).show())", "println(Money(250).<caret>)"),
                "plus", "show", "cents", "compareTo");
        assertCompletes(EXTENDED.replace("println(reader.read())", "println(reader.<caret>)"), "read", "close");
        assertCompletes(EXTENDED.replace("else e -> println(e.getMessage())", "else e -> println(e.<caret>)"), "getMessage");
        assertCompletes(EXTENDED.replace("fn show() -> String = cents + \"\"", "fn show() -> String = <caret>"), "cents", "plus", "compareTo");
        List<String> statement = complete(EXTENDED.replace("    loud.start()", "    <caret>\n    loud.start()"));
        assertTrue(statement.toString(), statement.containsAll(List.of("use", "break", "continue", "print", "readLine")));
    }

    public void testFindsAndRenamesDataClassMethodsAndEnumFields() {
        AxlFile file = (AxlFile) myFixture.configureByText("Extended.axl", EXTENDED);
        AxlTypeDeclaration money = file.typeDeclarations().get(3);
        assertEquals(1, myFixture.findUsages(money.methods().get(1)).size());
        assertEquals(1, myFixture.findUsages(money.methods().get(2)).size());
        assertEquals(5, myFixture.findUsages(money.fields().getFirst()).size());
        assertEquals(2, myFixture.findUsages(file.typeDeclarations().getFirst().fields().getFirst()).size());
        myFixture.renameElement(money.methods().get(2), "text");
        String text = myFixture.getFile().getText();
        assertTrue(text, text.contains("fn text() -> String = cents") && text.contains(".plus(Money(99)).text())"));
        myFixture.renameElement(file.typeDeclarations().getFirst().fields().getFirst(), "weight");
        text = myFixture.getFile().getText();
        assertTrue(text, text.contains("enum Planet(val weight: Double") && text.contains("println(Planet.EARTH.weight)")
                && text.contains("* p.weight /"));
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
