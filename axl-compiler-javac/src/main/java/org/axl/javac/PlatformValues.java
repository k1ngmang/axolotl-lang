package org.axl.javac;

import java.util.List;
import java.util.Set;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.PackageElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;

/**
 * Which declarations say nothing about null, for strict null mode: their values may be null, as if
 * they had the type `T?`.
 */
final class PlatformValues {

    private PlatformValues() {}

    /** A method or field whose result says nothing about null; locals, classes and constants are not. */
    static boolean isPlatform(Element element) {
        if (element == null || element.getKind() != ElementKind.METHOD && element.getKind() != ElementKind.FIELD) {
            return false;
        }
        // Constants like `System.out` are set once at class init; flagging them would bury real problems.
        if (element.getKind() == ElementKind.FIELD && element.getModifiers().containsAll(Set.of(Modifier.STATIC, Modifier.FINAL))) {
            return false;
        }
        TypeMirror type = element instanceof ExecutableElement method ? method.getReturnType() : element.asType();
        if (type.getKind().isPrimitive() || type.getKind() == TypeKind.VOID) {
            return false;
        }
        return !trusted(element) && !annotated(element, type, List.of("NonNull", "NotNull", "Nonnull", "Nullable"));
    }

    /** Whether a declaration states that it accepts or returns null. */
    static boolean isNullable(Element element, TypeMirror type) {
        return annotated(element, type, List.of("Nullable"));
    }

    /**
     * Axolotl's runtime, `Objects.requireNonNull`, code in a `@NullMarked` scope, and the stream
     * operations pipes expand to state or guarantee their nullness.
     */
    static boolean trusted(Element element) {
        for (Element e = element; e != null; e = e.getEnclosingElement()) {
            if (e instanceof PackageElement pkg && (pkg.getQualifiedName().contentEquals("axl.lang")
                    || pkg.getQualifiedName().contentEquals("java.util.stream"))) {
                return true;
            }
            if (e instanceof TypeElement type && type.getQualifiedName().contentEquals("java.util.Objects")
                    && element.getSimpleName().toString().startsWith("requireNonNull")) {
                return true;
            }
            if (e instanceof TypeElement type && type.getQualifiedName().contentEquals("java.util.Collection")
                    && element.getSimpleName().contentEquals("stream")) {
                return true;
            }
            if (hasAnnotation(e.getAnnotationMirrors(), "NullMarked")) {
                return true;
            }
        }
        return false;
    }

    private static boolean annotated(Element element, TypeMirror type, List<String> names) {
        return names.stream().anyMatch(n -> hasAnnotation(element.getAnnotationMirrors(), n)
                || hasAnnotation(type.getAnnotationMirrors(), n));
    }

    static boolean hasAnnotation(List<? extends AnnotationMirror> annotations, String simpleName) {
        return annotations.stream().anyMatch(a -> a.getAnnotationType().asElement().getSimpleName().contentEquals(simpleName));
    }
}
