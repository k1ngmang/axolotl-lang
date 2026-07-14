package org.axl.intellij.run;

import com.intellij.codeInsight.daemon.GutterMark;
import com.intellij.execution.actions.ConfigurationContext;
import com.intellij.execution.actions.ConfigurationFromContext;
import com.intellij.execution.junit.JUnitConfiguration;
import com.intellij.execution.junit.JUnitUtil;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElement;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.testFramework.LightProjectDescriptor;
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.jetbrains.annotations.NotNull;

/** Classes with `@Test` methods run as JUnit configurations of the generated class. */
public class AxlJUnitTest extends LightJavaCodeInsightFixtureTestCase {

    private static final String TESTS = """
            package app

            import org.junit.jupiter.api.Test

            class ParseTest {
                @Test
                fn parsesANumber() {
                    println(1)
                }

                fn helper() -> Int = 1
            }

            data class Point(val x: Int)

            fn main() {
                println(2)
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
            myFixture.addFileToProject("axl/lang/" + name + ".java", Files.readString(runtime.resolve(name + ".java")));
        }
        myFixture.addFileToProject("org/jspecify/annotations/NullMarked.java",
                "package org.jspecify.annotations;\npublic @interface NullMarked {}\n");
        // As in JUnit 5, `@Test` is `@Testable`, which is what IntelliJ looks for.
        myFixture.addFileToProject("org/junit/platform/commons/annotation/Testable.java", """
                package org.junit.platform.commons.annotation;
                @java.lang.annotation.Target({java.lang.annotation.ElementType.METHOD, java.lang.annotation.ElementType.TYPE,
                        java.lang.annotation.ElementType.ANNOTATION_TYPE})
                @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)
                public @interface Testable {}
                """);
        myFixture.addFileToProject("org/junit/jupiter/api/Test.java", """
                package org.junit.jupiter.api;
                @java.lang.annotation.Target(java.lang.annotation.ElementType.METHOD)
                @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)
                @org.junit.platform.commons.annotation.Testable
                public @interface Test {}
                """);
    }

    public void testSeesTheLightClassOfATestClassAsATest() {
        myFixture.addFileToProject("app/ParseTest.axl", TESTS);
        PsiClass cls = JavaPsiFacade.getInstance(getProject()).findClass("app.ParseTest", GlobalSearchScope.allScope(getProject()));
        assertNotNull(cls);
        assertTrue(JUnitUtil.isTestClass(cls));
        assertTrue(JUnitUtil.isTestClass(cls));
        assertFalse(JUnitUtil.isTestClass(JavaPsiFacade.getInstance(getProject())
                .findClass("app.ParseTest.Point", GlobalSearchScope.allScope(getProject()))));
    }

    public void testMarksTestClassesAndMethodsInTheGutter() {
        myFixture.configureByText("ParseTest.axl", TESTS);
        assertEquals(List.of("Run main", "Run test", "Run tests"), myFixture.findAllGutters().stream()
                .map(GutterMark::getTooltipText).filter(t -> t != null).sorted().toList());
    }

    public void testCreatesJUnitConfigurationsForAClassAndAMethod() {
        myFixture.addFileToProject("app/ParseTest.axl", TESTS);
        myFixture.configureByText("Main.axl", TESTS.replace("ParseTest", "Nested"));
        String text = myFixture.getFile().getText();
        JUnitConfiguration method = junitConfiguration(text.indexOf("parsesANumber"));
        assertEquals("app.Main$Nested", method.getPersistentData().getMainClassName());
        assertEquals("parsesANumber", method.getPersistentData().getMethodName());
        assertEquals(JUnitConfiguration.TEST_METHOD, method.getPersistentData().TEST_OBJECT);
        assertEquals("Main$Nested.parsesANumber", method.getName());
        JUnitConfiguration cls = junitConfiguration(text.indexOf("Nested"));
        assertEquals("app.Main$Nested", cls.getPersistentData().getMainClassName());
        assertEquals(JUnitConfiguration.TEST_CLASS, cls.getPersistentData().TEST_OBJECT);
        assertEquals("Main$Nested", cls.getName());
        // Elsewhere in a test class, the class runs; outside of one, nothing does.
        assertEquals(JUnitConfiguration.TEST_CLASS, junitConfiguration(text.indexOf("helper")).getPersistentData().TEST_OBJECT);
        assertNull(configurationAt(text.indexOf("Point"), JUnitConfiguration.class));
        assertNull(configurationAt(text.indexOf("println(2)"), JUnitConfiguration.class));
    }

    private JUnitConfiguration junitConfiguration(int offset) {
        JUnitConfiguration configuration = configurationAt(offset, JUnitConfiguration.class);
        assertNotNull(configuration);
        return configuration;
    }

    private <T> T configurationAt(int offset, Class<T> type) {
        PsiElement element = myFixture.getFile().findElementAt(offset);
        assertNotNull(element);
        for (ConfigurationFromContext candidate : new ConfigurationContext(element).getConfigurationsFromContext()) {
            if (type.isInstance(candidate.getConfiguration())) {
                return type.cast(candidate.getConfiguration());
            }
        }
        return null;
    }
}
