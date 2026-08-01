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
            "java.util.Map.computeIfPresent/2",
            "java.util.NavigableMap.lowerKey/1", "java.util.NavigableMap.floorKey/1",
            "java.util.NavigableMap.ceilingKey/1", "java.util.NavigableMap.higherKey/1",
            "java.util.NavigableMap.lowerEntry/1", "java.util.NavigableMap.floorEntry/1",
            "java.util.NavigableMap.ceilingEntry/1", "java.util.NavigableMap.higherEntry/1",
            "java.util.NavigableMap.firstEntry/0", "java.util.NavigableMap.lastEntry/0",
            "java.util.NavigableMap.pollFirstEntry/0", "java.util.NavigableMap.pollLastEntry/0",
            "java.util.NavigableSet.lower/1", "java.util.NavigableSet.floor/1", "java.util.NavigableSet.ceiling/1",
            "java.util.NavigableSet.higher/1", "java.util.NavigableSet.pollFirst/0", "java.util.NavigableSet.pollLast/0",
            "java.util.concurrent.atomic.AtomicReference.get/0", "java.util.concurrent.atomic.AtomicReference.getAndSet/1",
            "java.util.concurrent.atomic.AtomicReference.getAcquire/0", "java.util.concurrent.atomic.AtomicReference.getPlain/0",
            "java.util.concurrent.atomic.AtomicReference.getOpaque/0", "java.util.concurrent.atomic.AtomicReference.getAndUpdate/1",
            "java.util.concurrent.atomic.AtomicReference.getAndAccumulate/2",
            "java.util.concurrent.atomic.AtomicReference.compareAndExchange/2",
            "java.util.concurrent.atomic.AtomicReference.compareAndExchangeAcquire/2",
            "java.util.concurrent.atomic.AtomicReference.compareAndExchangeRelease/2",
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

    // JDK classes none of whose methods return null: strings, numbers, immutable values, factories and
    // helpers. A method of one that does return null is listed in NULLABLE_IN_TRUSTED.
    private static final Set<String> NEVER_NULL_CLASSES = Set.of(
            "java.lang.String", "java.lang.StringBuilder", "java.lang.StringBuffer", "java.lang.CharSequence",
            "java.lang.Math", "java.lang.StrictMath", "java.lang.Object", "java.lang.Enum", "java.lang.Record",
            "java.lang.Runtime", "java.lang.Process", "java.lang.StackTraceElement", "java.lang.StackWalker",
            "java.util.Arrays", "java.util.Collections", "java.util.Objects", "java.util.Optional", "java.util.OptionalInt",
            "java.util.OptionalLong", "java.util.OptionalDouble", "java.util.UUID", "java.util.Locale", "java.util.Random",
            "java.util.Scanner", "java.util.StringJoiner", "java.util.StringTokenizer", "java.util.BitSet", "java.util.HexFormat",
            "java.util.Currency", "java.util.Formatter", "java.util.ServiceLoader", "java.util.Base64", "java.util.Base64.Encoder",
            "java.util.Base64.Decoder", "java.util.concurrent.Executors", "java.util.concurrent.TimeUnit",
            "java.util.concurrent.CompletableFuture", "java.util.concurrent.Executor", "java.util.concurrent.ExecutorService",
            "java.util.concurrent.ScheduledExecutorService", "java.util.concurrent.AbstractExecutorService",
            "java.util.concurrent.ThreadPoolExecutor", "java.util.concurrent.ScheduledThreadPoolExecutor",
            "java.util.concurrent.ForkJoinPool", "java.util.concurrent.CountDownLatch", "java.util.concurrent.Semaphore",
            "java.util.concurrent.locks.Lock", "java.util.concurrent.locks.ReadWriteLock", "java.util.concurrent.locks.ReentrantLock",
            "java.util.concurrent.locks.ReentrantReadWriteLock", "java.util.concurrent.locks.ReentrantReadWriteLock.ReadLock",
            "java.util.concurrent.locks.ReentrantReadWriteLock.WriteLock", "java.util.concurrent.locks.StampedLock",
            "java.util.regex.Pattern", "java.util.regex.Matcher", "java.util.regex.MatchResult",
            "java.io.PrintStream", "java.io.PrintWriter", "java.io.StringWriter", "java.io.StringReader",
            "java.io.ByteArrayOutputStream", "java.io.ByteArrayInputStream", "java.io.InputStream", "java.io.OutputStream",
            "java.io.Reader", "java.io.Writer", "java.io.BufferedReader", "java.io.BufferedWriter", "java.io.InputStreamReader",
            "java.io.OutputStreamWriter", "java.io.File",
            "java.nio.file.Files", "java.nio.file.Path", "java.nio.file.Paths", "java.nio.file.FileSystems", "java.nio.file.FileSystem",
            "java.nio.charset.Charset", "java.nio.charset.StandardCharsets", "java.net.URLEncoder", "java.net.URLDecoder");
    // Packages none of whose methods return null: values of java.time and java.math, formats of java.text.
    private static final Set<String> NEVER_NULL_PACKAGES = Set.of("java.time", "java.time.format", "java.time.temporal",
            "java.time.chrono", "java.time.zone", "java.text", "java.math", "java.net.http");
    // The methods of those classes and packages that do return null when there is nothing to give.
    private static final Set<String> NULLABLE_IN_TRUSTED = Set.of(
            "java.util.Scanner.findInLine", "java.util.Scanner.findWithinHorizon",
            "java.util.regex.Matcher.group", "java.util.regex.MatchResult.group", "java.io.BufferedReader.readLine",
            "java.io.File.getParent", "java.io.File.getParentFile", "java.io.File.list", "java.io.File.listFiles",
            "java.nio.file.Files.probeContentType", "java.nio.file.Files.getAttribute", "java.nio.file.Path.getFileName",
            "java.nio.file.Path.getParent", "java.nio.file.Path.getRoot", "java.util.concurrent.ForkJoinPool.getUncaughtExceptionHandler",
            "java.time.format.DateTimeFormatter.getZone", "java.time.format.DateTimeFormatter.getResolverFields",
            "java.time.chrono.Chronology.getCalendarType", "java.time.zone.ZoneRules.getTransition",
            "java.time.zone.ZoneRules.nextTransition", "java.time.zone.ZoneRules.previousTransition");
    // Collection views, iterators and copies, which every implementation gives; found through the supertypes.
    private static final Set<String> NEVER_NULL_MEMBERS = Set.of("java.util.Map.entrySet/0", "java.util.Map.keySet/0",
            "java.util.Map.values/0", "java.lang.Iterable.iterator/0", "java.lang.Iterable.spliterator/0",
            "java.util.Collection.toArray/0", "java.util.Collection.toArray/1", "java.util.Collection.stream/0",
            "java.util.Collection.parallelStream/0", "java.util.List.subList/2", "java.util.List.listIterator/0",
            "java.util.List.listIterator/1", "java.util.SequencedCollection.reversed/0", "java.util.SequencedMap.reversed/0",
            "java.util.SequencedMap.sequencedKeySet/0", "java.util.SequencedMap.sequencedValues/0",
            "java.util.SequencedMap.sequencedEntrySet/0", "java.util.SortedMap.headMap/1", "java.util.SortedMap.tailMap/1",
            "java.util.SortedMap.subMap/2", "java.util.NavigableMap.headMap/2", "java.util.NavigableMap.tailMap/2",
            "java.util.NavigableMap.subMap/4", "java.util.NavigableMap.descendingMap/0", "java.util.NavigableMap.navigableKeySet/0",
            "java.util.NavigableMap.descendingKeySet/0", "java.util.SortedSet.headSet/1", "java.util.SortedSet.tailSet/1",
            "java.util.SortedSet.subSet/2", "java.util.NavigableSet.headSet/2", "java.util.NavigableSet.tailSet/2",
            "java.util.NavigableSet.subSet/4", "java.util.NavigableSet.descendingSet/0", "java.util.NavigableSet.descendingIterator/0",
            "java.util.Deque.descendingIterator/0");
    // The getters of Class and the reflection classes that the specification makes non-null; the others,
    // such as getAnnotation, getSuperclass, getEnumConstants or getRecordComponents, return null for a class
    // that has no such thing.
    private static final Set<String> CLASS_GETTERS = Set.of("getName", "getSimpleName", "getTypeName", "getPackageName",
            "descriptorString", "toGenericString", "getMethods", "getDeclaredMethods", "getFields", "getDeclaredFields",
            "getConstructors", "getDeclaredConstructors", "getInterfaces", "getGenericInterfaces", "getTypeParameters",
            "getAnnotations", "getDeclaredAnnotations", "getAnnotationsByType", "getDeclaredAnnotationsByType", "getMethod",
            "getDeclaredMethod", "getField", "getDeclaredField", "getConstructor", "getDeclaredConstructor", "arrayType",
            "getNestHost", "getNestMembers", "getModule", "asSubclass");
    private static final Set<String> REFLECTION_GETTERS = Set.of("getName", "getType", "getGenericType", "getReturnType",
            "getGenericReturnType", "getParameterTypes", "getGenericParameterTypes", "getExceptionTypes",
            "getGenericExceptionTypes", "getDeclaringClass", "getAccessor", "getParameters", "getParameterizedType",
            "getAnnotations", "getDeclaredAnnotations", "getAnnotationsByType", "getDeclaredAnnotationsByType",
            "getTypeParameters", "newInstance", "toGenericString", "getActualTypeArguments", "getRawType", "getBounds",
            "getUpperBounds", "getLowerBounds", "getGenericComponentType");

    /**
     * JDK methods that never return null although nothing says so: string, number and time values,
     * collection views and factories, files, executors and locks, and reflection getters that the
     * specification makes non-null. Without them strict mode would flag `s.trim().length()`,
     * `for (e in m.entrySet())` and `pool.submit({ -> ... })`.
     * Methods that return null for a missing value stay untrusted: `Map.get`, `Queue.poll`,
     * `System.getProperty`, `Class.getAnnotation`, `BufferedReader.readLine`, `Matcher.group`...
     */
    private static boolean neverNull(Element element) {
        if (!(element instanceof ExecutableElement method) || !(method.getEnclosingElement() instanceof TypeElement owner)) {
            return false;
        }
        String type = owner.getQualifiedName().toString();
        String name = method.getSimpleName().toString();
        int arity = method.getParameters().size();
        if (name.equals("toString") && arity == 0) {
            return true;
        }
        if (NULLABLE_IN_TRUSTED.contains(type + "." + name)) {
            return false;
        }
        String pkg = packageOf(owner);
        if (NEVER_NULL_PACKAGES.contains(pkg)) {
            // `TemporalAccessor.query` gives what the query finds, and a format parsed at a position gives null when it fails.
            return !(pkg.startsWith("java.time") && name.equals("query")) && !(pkg.equals("java.text") && name.startsWith("parse") && arity == 2);
        }
        if (NEVER_NULL_CLASSES.contains(type) || listed(NEVER_NULL_MEMBERS, owner, name + "/" + arity)) {
            return true;
        }
        boolean isStatic = method.getModifiers().contains(Modifier.STATIC);
        return switch (type) {
            // Integer.getInteger and Long.getLong read system properties and return null when one is missing.
            case "java.lang.Integer", "java.lang.Long", "java.lang.Double", "java.lang.Boolean", "java.lang.Character",
                 "java.lang.Short", "java.lang.Byte", "java.lang.Float" -> !name.startsWith("get");
            // `List.of`, `Map.copyOf`, `Map.entry` and the like.
            case "java.util.List", "java.util.Set", "java.util.Map", "java.net.URI", "java.net.URL" -> isStatic;
            // Comparators and functions are made and combined, never null; what they compute is their own business.
            case "java.util.Comparator", "java.util.Map.Entry" -> isStatic || method.isDefault();
            case "java.lang.Class" -> CLASS_GETTERS.contains(name);
            case "java.lang.System" -> Set.of("lineSeparator", "getProperties", "getLogger").contains(name);
            case "java.lang.Thread" -> Set.of("currentThread", "getName", "getStackTrace", "ofPlatform", "ofVirtual",
                    "startVirtualThread").contains(name);
            case "java.lang.Throwable" -> Set.of("getStackTrace", "getSuppressed", "fillInStackTrace", "initCause").contains(name);
            default -> pkg.equals("java.util.function") && (isStatic || method.isDefault())
                    || pkg.equals("java.lang.reflect") && REFLECTION_GETTERS.contains(name);
        };
    }

    private static String packageOf(Element element) {
        for (Element e = element; e != null; e = e.getEnclosingElement()) {
            if (e instanceof PackageElement pkg) {
                return pkg.getQualifiedName().toString();
            }
        }
        return "";
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
