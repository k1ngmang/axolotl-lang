package org.axl.intellij.java;

import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.psi.PsiFileFactory;
import com.intellij.psi.PsiMethod;
import com.intellij.testFramework.LightProjectDescriptor;
import com.intellij.testFramework.PsiTestUtil;
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase;
import java.nio.file.Path;
import java.util.List;
import org.axl.compiler.JavaClasses;
import org.axl.compiler.ReflectedClasses;
import org.axl.compiler.Translator;
import org.axl.intellij.AxlFileType;
import org.axl.intellij.psi.AxlFile;
import org.jetbrains.annotations.NotNull;

public class PsiJavaClassesTest extends LightJavaCodeInsightFixtureTestCase {

    private static final String USES = """
            package app

            import java.util.List
            import lib.Option
            import lib.Option.None
            import lib.Option.Some
            import lib.Texts
            import lib.Transformer

            class Doubler : Transformer<Int, Int> {
                fn apply(input: Int) -> Int = input * 2
            }

            fn show(o: Option<String>) -> String = when (o) {
                is Some -> o.value().toUpperCase()
                is None -> "-"
            }

            fn joined(parts: List<String>) -> String = Texts.join(parts)

            fn counted(parts: List<String>) -> Int = Texts.count(parts)
            """;

    @Override
    protected @NotNull LightProjectDescriptor getProjectDescriptor() {
        return JAVA_21;
    }

    public void testTranslatesWithTheLibrariesOfTheModule() {
        addLibrary();
        AxlFile file = (AxlFile) myFixture.addFileToProject("app/Main.axl", USES);
        String java = translation(file);
        assertTrue(java, java.contains("public Integer apply(Integer input)"));
        assertTrue(java, java.contains("case Some<String> o$1 ->"));
        assertTrue(java, java.contains("Texts.join(parts.toArray(new String[0]))"));
        assertTrue(java, java.contains("Texts.count(parts.toArray(new String[0]))"));
        // Java code sees the method that implements the library's interface.
        PsiMethod apply = myFixture.findClass("app.Main.Doubler").findMethodsByName("apply", false)[0];
        assertEquals("java.lang.Integer", apply.getReturnType().getCanonicalText());
    }

    public void testChecksAWhenOverTheTypesOfALibrary() {
        addLibrary();
        myFixture.configureByText("Main.axl", """
                package app

                import lib.Level
                import lib.Option
                import lib.Option.Some

                fn show(o: Option<String>) -> String = when (o) {
                    is Some -> o.value()
                }

                fn weight(l: Level) -> Int = when (l) {
                    Level.LOW -> 1
                    Level.MID -> 2
                    Level.HIGH -> 3
                }
                """);
        assertEquals(List.of("this `when` does not test `is None`; add a branch for each, or an `else ->` branch"), errors());
    }

    public void testTranslatesWithTheJavaSourcesOfTheModule() {
        myFixture.addClass("""
                package shapes;

                public sealed interface Shape {
                    record Circle(int r) implements Shape {}

                    record Dot() implements Shape {}
                }
                """);
        myFixture.addClass("""
                package shapes;

                public interface Measure<T> {
                    T size();
                }
                """);
        myFixture.configureByText("Area.axl", """
                package app

                import shapes.Shape
                import shapes.Shape.Circle

                fn area(s: Shape) -> Int = when (s) {
                    is Circle -> 3 * s.r() * s.r()
                }
                """);
        assertEquals(List.of("this `when` does not test `is Dot`; add a branch for each, or an `else ->` branch"), errors());
        myFixture.configureByText("Ruler.axl", """
                package app

                import shapes.Measure

                class Ruler : Measure<Long> {
                    fn size() -> Long = 3L
                }
                """);
        String java = translation((AxlFile) myFixture.getFile());
        assertTrue(java, java.contains("public Long size()"));
    }

    public void testKeepsTheClassesOfAModuleUntilItsRootsChange() {
        AxlFile file = (AxlFile) myFixture.addFileToProject("app/Main.axl", "package app\n");
        JavaClasses first = PsiJavaClasses.of(file);
        assertInstanceOf(first, PsiJavaClasses.class);
        assertSame(first, PsiJavaClasses.of(file));
        assertNull(first.shape("lib.Option"));
        addLibrary();
        JavaClasses second = PsiJavaClasses.of(file);
        assertNotSame(first, second);
        assertEquals(List.of("lib.Option.Some", "lib.Option.None"), second.shape("lib.Option").permitted());
        assertEquals(List.of("LOW", "MID", "HIGH"), second.shape("lib.Level").constants());
    }

    public void testReadsTheJdkOutsideAModule() {
        AxlFile file = (AxlFile) PsiFileFactory.getInstance(getProject()).createFileFromText("Main.axl", AxlFileType.INSTANCE, "package app\n");
        assertSame(ReflectedClasses.JDK, PsiJavaClasses.of(file));
    }

    private static String translation(AxlFile file) {
        return Translator.translate(file.sourcePath(), file.getName(), file.getText(), TranslationOptions.of(file)).unit().javaSource();
    }

    private List<String> errors() {
        return myFixture.doHighlighting().stream().filter(h -> h.getSeverity() == HighlightSeverity.ERROR)
                .map(h -> h.getDescription()).toList();
    }

    /** Puts the jar of src/testLibrary on the module's classpath for the test. */
    private void addLibrary() {
        Path jar = Path.of(System.getProperty("axl.testLibrary"));
        PsiTestUtil.addLibrary(getTestRootDisposable(), getModule(), "lib", jar.getParent() + "/", jar.getFileName().toString());
    }
}
