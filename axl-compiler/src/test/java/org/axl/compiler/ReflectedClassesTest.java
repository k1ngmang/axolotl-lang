package org.axl.compiler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class ReflectedClassesTest {

    sealed interface Option<T> permits Some, None {}

    record Some<T>(boolean present, T value) implements Option<T> {}

    record None<T>(boolean present) implements Option<T> {}

    public static final class Checks {
        public static String text(String value) {
            return value;
        }

        public static int length(String value) {
            return value.length();
        }
    }

    private final JavaClasses classes = ReflectedClasses.JDK;

    @Test
    void readsTheTypeParametersAndSupertypesOfAJdkClass() {
        JavaClasses.ClassShape list = classes.shape("java.util.ArrayList");
        assertEquals(List.of("E"), list.typeParameters());
        assertTrue(list.supertypes().contains(new JavaClasses.Supertype("java.util.List", List.of("E"))));
        assertFalse(list.isInterface());
        assertNull(classes.shape("java.util.NoSuchClass"));
    }

    @Test
    void readsNestedClassesByTheirCanonicalNames() {
        String some = "org.axl.compiler.ReflectedClassesTest.Some";
        JavaClasses.ClassShape shape = classes.shape(some);
        assertEquals(List.of("T"), shape.typeParameters());
        assertEquals(List.of("present", "value"), shape.components());
        assertTrue(shape.supertypes().contains(
                new JavaClasses.Supertype("org.axl.compiler.ReflectedClassesTest.Option", List.of("T"))));
        assertEquals(List.of(some, "org.axl.compiler.ReflectedClassesTest.None"),
                classes.shape("org.axl.compiler.ReflectedClassesTest.Option").permitted());
    }

    @Test
    void readsTheConstantsOfAnEnum() {
        assertEquals(List.of("NANOSECONDS", "MICROSECONDS", "MILLISECONDS", "SECONDS", "MINUTES", "HOURS", "DAYS"),
                classes.shape("java.util.concurrent.TimeUnit").constants());
        assertEquals(List.of(), classes.shape("java.util.ArrayList").constants());
    }

    @Test
    void tellsWhereAnInterfaceHasATypeVariable() {
        List<JavaClasses.MethodShape> methods = classes.methods("java.util.function.Function");
        assertTrue(methods.contains(new JavaClasses.MethodShape("apply", List.of(true), true)));
        assertEquals(List.of(), classes.methods("java.util.ArrayList"));
    }

    @Test
    void readsTheErasedParametersOfMethodsAndConstructors() {
        assertEquals(List.of(new JavaClasses.Signature(List.of("java.util.concurrent.CompletableFuture[]"), true)),
                classes.signatures("java.util.concurrent.CompletableFuture", "allOf"));
        assertTrue(classes.signatures("java.util.Arrays", "asList")
                .contains(new JavaClasses.Signature(List.of("T[]"), true)));
        List<JavaClasses.Signature> builders = classes.signatures("java.lang.ProcessBuilder", "<init>");
        assertTrue(builders.contains(new JavaClasses.Signature(List.of("java.util.List"), false)));
        assertTrue(builders.contains(new JavaClasses.Signature(List.of("java.lang.String[]"), true)));
        assertEquals(List.of(), classes.signatures("java.util.NoSuchClass", "of"));
    }

    @Test
    void tellsWhichStaticMethodsGiveTheTypeOfTheirFirstParameter() {
        assertEquals("", classes.validated("java.util.Objects", "requireNonNull", 2));
        assertEquals("java.lang.String", classes.validated("org.axl.compiler.ReflectedClassesTest.Checks", "text", 1));
        assertNull(classes.validated("org.axl.compiler.ReflectedClassesTest.Checks", "length", 1));
        assertNull(classes.validated("java.util.Objects", "toString", 1));
        assertNull(classes.validated("java.util.Objects", "requireNonNull", 3));
    }
}
