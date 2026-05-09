package org.axl.javac;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.PackageElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;

/**
 * Which declarations say nothing about null, for strict null mode: their values may be null, as if
 * they had the type `T?`.
 */
final class PlatformValues {

    // JDK methods that NullAway's library models treat as returning null, by name and parameter count.
    private static final Set<String> MODELED_NULLABLE = Set.of("java.util.Map.get/1", "java.lang.ref.Reference.get/0");
    // JDK methods that return null by contract, for an empty collection, a missing key or no previous
    // value, which NullAway's models leave out. Overrides are found through the supertypes.
    private static final Set<String> NULL_BY_CONTRACT = Set.of("java.util.Queue.poll/0", "java.util.Queue.peek/0",
            "java.util.Deque.pollFirst/0", "java.util.Deque.pollLast/0", "java.util.Deque.peekFirst/0",
            "java.util.Deque.peekLast/0", "java.util.concurrent.BlockingQueue.poll/2",
            "java.util.Map.put/2", "java.util.Map.remove/1", "java.util.Map.putIfAbsent/2", "java.util.Map.replace/2",
            "java.util.NavigableMap.lowerKey/1", "java.util.NavigableMap.floorKey/1",
            "java.util.NavigableMap.ceilingKey/1", "java.util.NavigableMap.higherKey/1",
            "java.util.NavigableMap.lowerEntry/1", "java.util.NavigableMap.floorEntry/1",
            "java.util.NavigableMap.ceilingEntry/1", "java.util.NavigableMap.higherEntry/1",
            "java.util.NavigableMap.firstEntry/0", "java.util.NavigableMap.lastEntry/0",
            "java.util.NavigableMap.pollFirstEntry/0", "java.util.NavigableMap.pollLastEntry/0",
            "java.util.NavigableSet.lower/1", "java.util.NavigableSet.floor/1", "java.util.NavigableSet.ceiling/1",
            "java.util.NavigableSet.higher/1", "java.util.NavigableSet.pollFirst/0", "java.util.NavigableSet.pollLast/0",
            "java.util.concurrent.atomic.AtomicReference.get/0", "java.util.concurrent.atomic.AtomicReference.getAndSet/1",
            "java.lang.ThreadLocal.get/0");

    private PlatformValues() {}

    /** A method or field whose result says nothing about null; locals, classes and constants are not. */
    static boolean isPlatform(Element element) {
        if (element == null || element.getKind() != ElementKind.METHOD && element.getKind() != ElementKind.FIELD) {
            return false;
        }
        // JDK constants like `System.out` are set once at class init; flagging them would bury real problems.
        if (element.getKind() == ElementKind.FIELD && element.getModifiers().containsAll(Set.of(Modifier.STATIC, Modifier.FINAL))
                && (isJdk(element) || element instanceof VariableElement field && field.getConstantValue() != null)) {
            return false;
        }
        TypeMirror type = element instanceof ExecutableElement method ? method.getReturnType() : element.asType();
        if (type.getKind().isPrimitive() || type.getKind() == TypeKind.VOID || neverNull(element)) {
            return false;
        }
        return !trusted(element) && !annotated(element, type, List.of("NonNull", "NotNull", "Nonnull", "Nullable"));
    }

    /**
     * JDK methods that never return null although nothing says so: string and number methods and the
     * immutable collection factories. Without them strict mode would flag `s.trim().length()`.
     */
    private static boolean neverNull(Element element) {
        if (!(element instanceof ExecutableElement method) || !(method.getEnclosingElement() instanceof TypeElement owner)) {
            return false;
        }
        String type = owner.getQualifiedName().toString();
        String name = method.getSimpleName().toString();
        if (name.equals("toString") && method.getParameters().isEmpty()) {
            return true;
        }
        return switch (type) {
            case "java.lang.String", "java.lang.StringBuilder", "java.lang.Math" -> true;
            // Integer.getInteger and Long.getLong read system properties and return null when one is missing.
            case "java.lang.Integer", "java.lang.Long", "java.lang.Double", "java.lang.Boolean", "java.lang.Character",
                 "java.lang.Short", "java.lang.Byte", "java.lang.Float" -> !name.startsWith("get");
            case "java.util.List", "java.util.Set", "java.util.Map" ->
                    method.getModifiers().contains(Modifier.STATIC) && (name.equals("of") || name.equals("copyOf"));
            default -> false;
        };
    }

    static boolean isJdk(Element element) {
        for (Element e = element; e != null; e = e.getEnclosingElement()) {
            if (e instanceof PackageElement pkg) {
                String name = pkg.getQualifiedName().toString();
                return name.startsWith("java.") || name.startsWith("javax.");
            }
        }
        return false;
    }

    /** Whether a declaration states that it accepts or returns null, or NullAway's models say it returns null. */
    static boolean isNullable(Element element, TypeMirror type) {
        return annotated(element, type, List.of("Nullable"))
                || element instanceof ExecutableElement method && method.getEnclosingElement() instanceof TypeElement owner
                && listed(MODELED_NULLABLE, owner, method.getSimpleName() + "/" + method.getParameters().size());
    }

    /** `poll` of a `Queue<String>` returns null when the queue is empty, although `String` has no `?`. */
    static boolean returnsNullByContract(ExecutableElement method) {
        return !method.getReturnType().getKind().isPrimitive() && method.getEnclosingElement() instanceof TypeElement owner
                && listed(NULL_BY_CONTRACT, owner, method.getSimpleName() + "/" + method.getParameters().size());
    }

    private static boolean listed(Set<String> methods, TypeElement type, String method) {
        if (methods.contains(type.getQualifiedName() + "." + method)) {
            return true;
        }
        List<TypeMirror> parents = new ArrayList<>(type.getInterfaces());
        parents.add(type.getSuperclass());
        for (TypeMirror parent : parents) {
            if (parent instanceof DeclaredType declared && listed(methods, (TypeElement) declared.asElement(), method)) {
                return true;
            }
        }
        return false;
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
