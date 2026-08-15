package org.axl.javac;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.axl.compiler.JavaClasses;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JavacClassesTest {

    @Test
    void readsTheClassesOfTheJdk() throws Exception {
        try (var classes = new JavacClasses(List.of(), List.of())) {
            JavaClasses.ClassShape list = classes.shape("java.util.ArrayList");
            assertEquals(List.of("E"), list.typeParameters());
            assertTrue(list.supertypes().contains(new JavaClasses.Supertype("java.util.List", List.of("E"))));
            assertFalse(list.isInterface());
            assertTrue(classes.shape("java.util.Map.Entry").isInterface());
            assertNull(classes.shape("java.util.NoSuchClass"));
            assertTrue(classes.methods("java.util.function.Function")
                    .contains(new JavaClasses.MethodShape("apply", List.of(true), true)));
            assertEquals(List.of(), classes.methods("java.util.ArrayList"));
            assertEquals(List.of(new JavaClasses.Signature(List.of("java.util.concurrent.CompletableFuture[]"), true)),
                    classes.signatures("java.util.concurrent.CompletableFuture", "allOf"));
            assertTrue(classes.signatures("java.util.Arrays", "asList")
                    .contains(new JavaClasses.Signature(List.of("T[]"), true)));
            List<JavaClasses.Signature> builders = classes.signatures("java.lang.ProcessBuilder", "<init>");
            assertTrue(builders.contains(new JavaClasses.Signature(List.of("java.util.List"), false)));
            assertTrue(builders.contains(new JavaClasses.Signature(List.of("java.lang.String[]"), true)));
        }
    }

    @Test
    void readsJavaSourcesAndCompiledClassesOfTheClasspath(@TempDir Path tmp) throws Exception {
        Path sources = Files.createDirectories(tmp.resolve("src/lib"));
        Files.writeString(sources.resolve("Feed.java"), """
                package lib;

                public interface Feed<T> {
                    T next();

                    void push(T item);
                }
                """);
        Files.writeString(sources.resolve("Shapes.java"), """
                package lib;

                public sealed interface Shapes<T> permits Shapes.Circle, Shapes.Square {
                    record Circle<T>(T radius) implements Shapes<T> {}

                    record Square<T>(T side, boolean filled) implements Shapes<T> {}
                }
                """);
        try (var classes = new JavacClasses(List.of(), List.of(tmp.resolve("src")))) {
            assertTrue(classes.methods("lib.Feed").contains(new JavaClasses.MethodShape("next", List.of(), true)));
            assertTrue(classes.methods("lib.Feed").contains(new JavaClasses.MethodShape("push", List.of(true), false)));
            JavaClasses.ClassShape square = classes.shape("lib.Shapes.Square");
            assertEquals(List.of("T"), square.typeParameters());
            assertEquals(List.of("side", "filled"), square.components());
            assertTrue(square.supertypes().contains(new JavaClasses.Supertype("lib.Shapes", List.of("T"))));
            assertEquals(List.of("lib.Shapes.Circle", "lib.Shapes.Square"), classes.shape("lib.Shapes").permitted());
        }
    }
}
