package org.axl.intellij;

import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiField;
import com.intellij.psi.PsiModifier;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.testFramework.LightProjectDescriptor;
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
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

    private PsiClass findClass(String name) {
        PsiClass cls = JavaPsiFacade.getInstance(getProject()).findClass(name, GlobalSearchScope.allScope(getProject()));
        assertNotNull(name, cls);
        return cls;
    }
}
