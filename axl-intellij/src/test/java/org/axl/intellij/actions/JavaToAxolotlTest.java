package org.axl.intellij.actions;

import com.intellij.psi.PsiJavaFile;
import com.intellij.testFramework.LightProjectDescriptor;
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase;
import org.jetbrains.annotations.NotNull;

/** Java outside the convertible subset is refused instead of turning into invalid Axolotl. */
public class JavaToAxolotlTest extends LightJavaCodeInsightFixtureTestCase {

    @Override
    protected @NotNull LightProjectDescriptor getProjectDescriptor() {
        return JAVA_21;
    }

    public void testRefusesWhatAxolotlCannotExpress() {
        assertRefused("Default.java", "public final class Default {\n    public static int one() { return 1; }\n}\n");
        assertRefused("Bits.java", inClass("public static int f(int a, int b) { return a & b; }"));
        assertRefused("Shift.java", inClass("public static int f(int a) { int x = a; x <<= 1; return x; }"));
        assertRefused("Chain.java", inClass("public static int f(int a) { int x = 0; int y = 0; x = y = a; return x; }"));
        assertRefused("Wild.java", inClass("public static int f(java.util.List<? extends Number> xs) { return xs.size(); }"));
        assertRefused("HexFloat.java", inClass("public static double f() { return 0x1p3; }"));
        assertRefused("Keyword.java", inClass("public static int f(int val) { return val; }"));
        assertRefused("Args.java", inClass("public static void main(String[] args) { System.out.println(args.length); }"));
        assertRefused("Is.java", inClass("public static int f(int is) { return is; }"));
        assertRefused("When.java", inClass("public static int f(int when) { return when; }"));
        assertRefused("Sealed.java", inClass("public static int f(int sealed) { return sealed; }"));
        assertRefused("Switch.java", inClass("public static int f(int n) { switch (n) { case 1: return 2; "
                + "default: return 3; } }"));
        assertRefused("Init.java", inClass("static { System.out.println(\"loaded\"); }"));
        assertRefused("Ctor.java", inClass("private Ctor() { System.out.println(\"x\"); }"));
        assertRefused("Prim.java", inClass("public static Class<?> f() { return int.class; }"));
        assertRefused("Wrapped.java", inClass("@SuppressWarnings(value = {\"a\"}) @java.lang.annotation.Target("
                + "@java.lang.annotation.Documented) public static int f() { return 1; }"));
        assertRefused("Read.java", inClass("public static String read(java.nio.file.Path p) throws java.io.IOException "
                + "{ return java.nio.file.Files.readString(p); }"));
    }

    public void testRefusesWhatTheTranslatorRejects() {
        assertRefused("Dollar.java", inClass("public static int f(int a$b) { return a$b; }"));
        assertRefused("JavaName.java", inClass("public static int f(int java) { return java; }"));
        assertRefused("MainArgs.java", inClass("public static void main(String[] a) { int args = 1; System.out.println(args); }"));
        assertRefused("Result.java", inClass("public static int f() { return 1; }"));
        assertRefused("Order.java", inClass("static final int A = compute();\nstatic final int B = 2;\n"
                + "static int compute() { return B; }"));
    }

    public void testDeclaresVariablesThatHoldNullNullable() {
        String axl = convert("Maybe.java", inClass("""
                static final String NONE = null;
                public static void f(boolean b) {
                    String r = null;
                    if (b) {
                        r = "x";
                    }
                    String s = "y";
                    System.out.println(r + s + NONE);
                }"""));
        assertTrue(axl, axl.contains("val NONE: String? = null\n"));
        assertTrue(axl, axl.contains("var r: String? = null\n"));
        assertTrue(axl, axl.contains("val s: String = \"y\"\n"));
    }

    public void testDeclaresMethodsThatReturnNullNullable() {
        String axl = convert("Find.java", inClass("""
                public static String find(int x) {
                    if (x > 0) {
                        return "positive";
                    }
                    return null;
                }
                public static String later(int x) {
                    String found = null;
                    if (x > 0) {
                        found = "positive";
                    }
                    return found;
                }
                public static String never(int x) {
                    return "x";
                }"""));
        assertTrue(axl, axl.contains("fn find(x: Int) -> String? {"));
        assertTrue(axl, axl.contains("fn later(x: Int) -> String? {"));
        assertTrue(axl, axl.contains("fn never(x: Int) -> String {"));
    }

    public void testTurnsNullableAnnotationsIntoNullableTypes() {
        myFixture.addFileToProject("org/jspecify/annotations/Nullable.java",
                "package org.jspecify.annotations;\n@java.lang.annotation.Target(java.lang.annotation.ElementType.TYPE_USE)\n"
                        + "public @interface Nullable {}\n");
        String axl = convert("Keys.java", """
                package util;
                import org.jspecify.annotations.Nullable;
                public final class Keys {
                    public static @Nullable String find(@Nullable String key, java.util.List<@Nullable String> xs) {
                        return key;
                    }
                }
                """);
        assertTrue(axl, axl.startsWith("package util\nimport java.util.List\n\n"));
        assertTrue(axl, axl.contains("fn find(key: String?, xs: List<String?>) -> String? {"));
    }

    public void testKeepsMembersPrivateThatNoOtherFileUses() {
        myFixture.addFileToProject("util/User.java", "package util;\nclass User {\n    int use() { return Access.shared(); }\n}\n");
        String axl = convert("Access.java", inClass("""
                static final int LIMIT = 3;
                private static int hidden() { return LIMIT; }
                static int helper() { return hidden(); }
                protected static int shared() { return helper(); }
                public static int open() { return shared(); }"""));
        assertTrue(axl, axl.contains("private val LIMIT: Int = 3\n"));
        assertTrue(axl, axl.contains("private fn hidden() -> Int {"));
        assertTrue(axl, axl.contains("private fn helper() -> Int {"));
        assertTrue(axl, axl.contains("\nfn shared() -> Int {"));
        assertTrue(axl, axl.contains("\nfn open() -> Int {"));
    }

    public void testKeepsComments() {
        String axl = convert("Notes.java", """
                // License header.
                package util;

                /** Notes about the class. */
                public final class Notes {
                    /** No instances. */
                    private Notes() {}

                    /** The limit. */
                    static final int LIMIT = 3;

                    // Before f.
                    public static int f(int a) {
                        // Start.
                        int b = a + LIMIT; // Trailing.
                        /* Block. */
                        return b;
                    }

                    // At the end.
                }
                """);
        assertEquals("""
                // License header.
                /** Notes about the class. */
                package util

                /** The limit. */
                private val LIMIT: Int = 3

                // Before f.
                fn f(a: Int) -> Int {
                    // Start.
                    val b: Int = a + LIMIT // Trailing.
                    /* Block. */
                    return b
                }

                // At the end.
                """, axl);
        assertRefused("Inside.java", inClass("public static int f(int a) { return a + /* one */ 1; }"));
    }

    public void testConvertsLiteralsToTheirAxolotlForm() {
        String axl = convert("Lits.java", inClass("""
                public static double f() {
                    final int b = 0b101;
                    final int o = 017;
                    final int u = 0_7;
                    final double d = .5;
                    final double e = 5.;
                    final float f = 1.5F;
                    return b + o + d + e + f;
                }"""));
        assertTrue(axl, axl.contains("val b: Int = 5\n"));
        assertTrue(axl, axl.contains("val o: Int = 15\n"));
        assertTrue(axl, axl.contains("val u: Int = 7\n"));
        assertTrue(axl, axl.contains("val d: Double = 0.5\n"));
        assertTrue(axl, axl.contains("val e: Double = 5.0\n"));
        assertTrue(axl, axl.contains("val f: Float = 1.5f\n"));
    }

    public void testImportsOverloadedStaticMethodsAsFunctions() {
        String axl = convert("Static.java", "package util;\nimport static java.lang.Math.max;\n"
                + "public final class Static {\n    public static int f(int a) { return max(a, 1); }\n}\n");
        assertTrue(axl, axl.contains("import fn java.lang.Math.max\n"));
    }

    public void testKeepsUncheckedThrowsOut() {
        String axl = convert("Check.java",
                inClass("public static int f(int a) throws IllegalArgumentException { return a; }"));
        assertTrue(axl, axl.contains("fn f(a: Int) -> Int {"));
    }

    public void testImportsQualifiedAndNestedTypes() {
        String axl = convert("Sizes.java", inClass("""
                public static int size(java.util.List<String> xs) {
                    return xs.size();
                }
                public static int value(java.util.Map.Entry<String, Integer> e) {
                    return e.getValue();
                }"""));
        assertTrue(axl, axl.startsWith("package util\nimport java.util.List\nimport java.util.Map.Entry\n"));
        assertTrue(axl, axl.contains("fn size(xs: List<String>) -> Int {"));
        assertTrue(axl, axl.contains("fn value(e: Entry<String, Integer>) -> Int {"));
        assertRefused("Lists.java", inClass("public static int f(java.util.List<String> a, java.awt.List b) { return 1; }"));
    }

    public void testConvertsRecordsEnumsSealedInterfacesAndSwitches() {
        String axl = convert("Shapes.java", """
                package util;

                public final class Shapes {
                    private Shapes() {}

                    public sealed interface Shape permits Circle, Square {
                        String name();
                    }

                    public record Circle(String name, double radius) implements Shape {}

                    public record Square(String name, double side) implements Shape {}

                    public enum Color { RED, GREEN }

                    public static double area(Shape shape) {
                        return switch (shape) {
                            case Circle c -> 3.14 * c.radius() * c.radius();
                            case Square s -> s.side() * s.side();
                        };
                    }

                    public static String size(int n) {
                        return switch (n) {
                            case 1, 2 -> "few";
                            default -> throw new IllegalArgumentException("many");
                        };
                    }
                }
                """);
        assertTrue(axl, axl.contains("sealed interface Shape {\n    val name: String\n}\n"));
        assertTrue(axl, axl.contains("data class Circle(val name: String, val radius: Double) : Shape\n"));
        assertTrue(axl, axl.contains("enum Color { RED, GREEN }\n"));
        assertTrue(axl, axl.contains("""
                    return when (shape) {
                        is Circle -> 3.14 * shape.radius() * shape.radius()
                        is Square -> shape.side() * shape.side()
                    }
                """));
        assertTrue(axl, axl.contains("1, 2 -> \"few\"\n        else -> throw IllegalArgumentException(\"many\")\n"));
        assertFalse(axl, axl.contains("import util.Shapes"));
    }

    public void testConvertsAbstractMethodsOfAnInterfaceToSignaturesAndKeepsValues() {
        myFixture.addFileToProject("org/jspecify/annotations/Nullable.java",
                "package org.jspecify.annotations;\n@java.lang.annotation.Target(java.lang.annotation.ElementType.TYPE_USE)\n"
                        + "public @interface Nullable {}\n");
        String axl = convert("Notes.java", """
                package util;

                import org.jspecify.annotations.Nullable;

                public interface Notes {
                    String name();

                    @Nullable String label();

                    /** Adds a note. */
                    int add(String text, int at);

                    @Nullable String find(int id);

                    void clear();

                    void rename(@Nullable String name);

                    java.util.List<String> all(boolean sorted);
                }
                """);
        assertTrue(axl, axl.contains("interface Notes {\n    val name: String\n    val label: String?\n"));
        assertTrue(axl, axl.contains("    /** Adds a note. */\n    fn add(text: String, at: Int) -> Int\n"));
        assertTrue(axl, axl.contains("    fn find(id: Int) -> String?\n    fn clear()\n    fn rename(name: String?)\n"));
        assertTrue(axl, axl.contains("    fn all(sorted: Boolean) -> List<String>\n}\n"));
        assertTrue(axl, axl.contains("import java.util.List\n"));

        String nested = convert("Stores.java", """
                package util;

                public final class Stores {
                    private Stores() {}

                    public interface Store {
                        String get(String key);
                        void put(String key, String value);
                    }
                }
                """);
        assertTrue(nested, nested.contains("interface Store {\n    fn get(key: String) -> String\n    fn put(key: String, value: String)\n}\n"));
    }

    public void testRefusesInterfacesWithMethodsThatHaveNoAxolotlForm() {
        assertRefused("Default.java", "package util;\npublic interface Default {\n    default int one() { return 1; }\n}\n");
        assertRefused("Static.java", "package util;\npublic interface Static {\n    static int one() { return 1; }\n}\n");
        assertRefused("Private.java", "package util;\npublic interface Private {\n    int f();\n    private int one() { return 1; }\n}\n");
        assertRefused("Checked.java", "package util;\npublic interface Checked {\n    void close() throws java.io.IOException;\n}\n");
        assertRefused("Constant.java", "package util;\npublic interface Constant {\n    int LIMIT = 3;\n    int f();\n}\n");
        assertRefused("Nested.java", "package util;\npublic interface Nested {\n    class Inner {}\n}\n");
        assertTrue(JavaToAxolotl.isConvertible(configure("Fine.java",
                "package util;\npublic interface Fine {\n    int f(int a);\n    void g();\n    String name();\n}\n")));
    }

    public void testConvertsATopLevelRecordAndEnum() {
        assertEquals("package util\n\ndata class Point(val x: Int, val y: Int)\n",
                convert("Point.java", "package util;\npublic record Point(int x, int y) {}\n"));
        assertEquals("package util\n\nenum Light { ON, OFF }\n",
                convert("Light.java", "package util;\npublic enum Light { ON, OFF }\n"));
    }

    public void testConvertsAClassWithAConstructor() {
        String axl = convert("Counter.java", """
                package util;

                public class Counter {
                    public final String name;
                    public int step;
                    private int count = 0;

                    public Counter(String name, int step) {
                        this.name = name;
                        this.step = step;
                    }

                    public int next() {
                        count += step;
                        return this.count;
                    }

                    public static Counter start(String name) {
                        return new Counter(name, 1);
                    }
                }
                """);
        assertTrue(axl, axl.contains("class Counter(val name: String, var step: Int) {\n    private var count: Int = 0\n"));
        assertTrue(axl, axl.contains("    fn next() -> Int {\n        count += step\n        return count\n    }\n}\n"));
        assertTrue(axl, axl.contains("\nfn start(name: String) -> Counter {\n    return Counter(name, 1)\n}\n"));
    }

    public void testConvertsLoopsThrowConditionalsAndTypeTests() {
        String axl = convert("Flow.java", inClass("""
                public static int sum(java.util.List<Integer> xs) {
                    int s = 0;
                    for (int x : xs) {
                        s += x;
                    }
                    return s > 0 ? s : 0;
                }
                public static int length(Object o) {
                    if (!(o instanceof String text)) {
                        throw new IllegalArgumentException("not text");
                    }
                    return text.length();
                }
                public static boolean isText(Object o) {
                    return o instanceof String;
                }"""));
        assertTrue(axl, axl.contains("    for (x in xs) {\n        s += x\n    }\n    return if (s > 0) s else 0\n"));
        assertTrue(axl, axl.contains("    if (o !is String) {\n        throw IllegalArgumentException(\"not text\")\n    }\n"
                + "    return o.length()\n"));
        assertTrue(axl, axl.contains("return o is String\n"));
    }

    public void testKeepsAnnotationsClassLiteralsAndStarArguments() {
        myFixture.addFileToProject("util/Marker.java", """
                package util;
                @java.lang.annotation.Target({java.lang.annotation.ElementType.METHOD, java.lang.annotation.ElementType.TYPE,
                        java.lang.annotation.ElementType.FIELD, java.lang.annotation.ElementType.PARAMETER,
                        java.lang.annotation.ElementType.RECORD_COMPONENT})
                public @interface Marker {
                    String value() default "";
                    int weight() default 0;
                    Class<?> type() default Object.class;
                    String[] tags() default {};
                }
                """);
        myFixture.addFileToProject("org/junit/jupiter/api/Test.java",
                "package org.junit.jupiter.api;\npublic @interface Test {}\n");
        String axl = convert("Marked.java", """
                package util;

                import java.util.List;
                import org.junit.jupiter.api.Test;

                public final class Marked {
                    private Marked() {}

                    @Marker("record")
                    public record Point(@Marker("x") int x, int y) {}

                    @Marker(value = "class", weight = 2, type = Integer.class, tags = {"a", "b"})
                    public static final class Counter {
                        public final int start;
                        @Marker("prop")
                        public int count = 0;

                        public Counter(@Marker("c") int start) {
                            this.start = start;
                        }

                        @Marker(weight = -1)
                        public int next(@Marker("p") int step) {
                            count += step;
                            return count;
                        }

                        @Override
                        public String toString() {
                            return "Counter " + count;
                        }
                    }

                    @Deprecated
                    public static int old() {
                        return 1;
                    }

                    @Test
                    @Marker("value")
                    public static final int LIMIT = 3;

                    public static String name(Class<?> c, List<?> xs) {
                        return c.getSimpleName() + xs.size() + String.class.getName() + Marked.class.getSimpleName();
                    }
                }
                """);
        assertTrue(axl, axl.contains("\n@Marker(\"record\")\ndata class Point(@Marker(\"x\") val x: Int, val y: Int)\n"));
        assertTrue(axl, axl.contains("\n@Marker(value = \"class\", weight = 2, type = Int.class, tags = {\"a\", \"b\"})\n"
                + "class Counter(@Marker(\"c\") val start: Int) {\n    @Marker(\"prop\")\n    var count: Int = 0\n"));
        assertTrue(axl, axl.contains("    @Marker(weight = -1)\n    fn next(@Marker(\"p\") step: Int) -> Int {\n"));
        assertTrue(axl, axl.contains("    @Override\n    fn toString() -> String {\n"));
        assertTrue(axl, axl.contains("\n@Deprecated\nfn old() -> Int {\n"));
        assertTrue(axl, axl.contains("\n@Test\n@Marker(\"value\")\nval LIMIT: Int = 3\n"));
        assertTrue(axl, axl.contains("fn name(c: Class<*>, xs: List<*>) -> String {\n"
                + "    return c.getSimpleName() + xs.size() + String.class.getName() + Marked.class.getSimpleName()\n"));
        assertTrue(axl, axl.startsWith("package util\nimport java.util.List\nimport org.junit.jupiter.api.Test\n"));
        String imported = convert("Keep.java", "package util;\npublic final class Keep {\n"
                + "    @java.lang.annotation.Documented public static int f() { return 1; }\n}\n");
        assertTrue(imported, imported.startsWith("package util\nimport java.lang.annotation.Documented\n"));
        assertTrue(imported, imported.contains("\n@Documented\nfn f() -> Int {\n"));
    }

    public void testConvertsTypeParametersOfTypesAndMethods() {
        String axl = convert("Generics.java", """
                package util;

                import java.util.List;

                public final class Generics {
                    private Generics() {}

                    public record Pair<A, B>(A first, B second) {}

                    public interface Repository<K, V> {
                        V find(K key);
                        void save(K key, V value);
                        <R> List<R> all(R marker);
                    }

                    public static final class Box<T extends Comparable<T>> {
                        public final T value;

                        public Box(T value) {
                            this.value = value;
                        }

                        public boolean bigger(T other) {
                            return value.compareTo(other) > 0;
                        }
                    }

                    public static <T> T id(T x) {
                        return x;
                    }

                    public static <A, B> Pair<B, A> swap(Pair<A, B> p) {
                        return new Pair<>(p.second(), p.first());
                    }
                }
                """);
        assertTrue(axl, axl.contains("\ndata class Pair<A, B>(val first: A, val second: B)\n"));
        assertTrue(axl, axl.contains("\ninterface Repository<K, V> {\n    fn find(key: K) -> V\n    fn save(key: K, value: V)\n"
                + "    fn all<R>(marker: R) -> List<R>\n}\n"));
        assertTrue(axl, axl.contains("\nclass Box<T : Comparable<T>>(val value: T) {\n"));
        assertTrue(axl, axl.contains("    fn bigger(other: T) -> Boolean {\n        return value.compareTo(other) > 0\n    }\n"));
        assertTrue(axl, axl.contains("\nfn id<T>(x: T) -> T {\n    return x\n}\n"));
        assertTrue(axl, axl.contains("\nfn swap<A, B>(p: Pair<A, B>) -> Pair<B, A> {\n    return Pair<>(p.second(), p.first())\n}\n"));
        assertFalse(axl, axl.contains("import util"));
        assertEquals("package util\n\ndata class Box<T>(val value: T)\n", convert("Box.java", "package util;\npublic record Box<T>(T value) {}\n"));
        assertTrue(convert("Plain.java", inClass("public static <T extends Object> T id(T x) { return x; }")).contains("fn id<T : Object>(x: T) -> T {"));
        myFixture.addFileToProject("org/jspecify/annotations/Nullable.java",
                "package org.jspecify.annotations;\n@java.lang.annotation.Target(java.lang.annotation.ElementType.TYPE_USE)\n"
                        + "public @interface Nullable {}\n");
        String any = convert("Any.java", "package util;\nimport org.jspecify.annotations.Nullable;\npublic final class Any {\n"
                + "    public static <T extends @Nullable Object> T id(T x) { return x; }\n}\n");
        assertTrue(any, any.contains("fn id<T>(x: T) -> T {"));
    }

    public void testRefusesTypeParametersWithoutAOneToOneForm() {
        assertRefused("Bounds.java", inClass("public static <T extends Number & Comparable<T>> T max(T a) { return a; }"));
        assertRefused("Super.java", inClass("public static <T extends Comparable<? super T>> T max(T a) { return a; }"));
        assertRefused("Star.java", inClass("public static <T extends java.util.List<?>> int size(T xs) { return xs.size(); }"));
        assertRefused("Holder.java", "package util;\npublic final class Holder<T> {\n    public static int one() { return 1; }\n}\n");
    }

    public void testRefusesTypesAndPatternsWithoutAOneToOneForm() {
        assertRefused("Args.java", "package util;\npublic enum Args { A(1); Args(int v) {} }\n");
        assertRefused("Two.java", "package util;\npublic class Two {\n    int a;\n    public Two(int a) { this.a = a; }\n"
                + "    public Two() { this.a = 1; }\n}\n");
        assertRefused("Logic.java", "package util;\npublic class Logic {\n    int a;\n"
                + "    public Logic(int a) { this.a = a * 2; }\n}\n");
        assertRefused("Reads.java", "package util;\npublic class Reads {\n    int a;\n    int b = a + 1;\n"
                + "    public Reads(int a) { this.a = a; }\n}\n");
        assertRefused("Guard.java", inClass("public static int f(Object o) { return switch (o) { "
                + "case String s when s.isEmpty() -> 0; default -> 1; }; }"));
        assertRefused("Changes.java", inClass("public static int f(Object o) { if (o instanceof String s) { o = 1; "
                + "return s.length(); } return 0; }"));
        assertRefused("Yield.java", inClass("public static int f(int n) { return switch (n) { case 1 -> { yield 2; } "
                + "default -> 3; }; }"));
        assertRefused("Inner.java", inClass("class Inner { int a; }"));
        assertRefused("Hidden.java", "package util;\npublic class Hidden {\n    private final int a;\n"
                + "    public Hidden(int a) { this.a = a; }\n}\n");
    }

    public void testConvertsEnumsWithFieldsAndAConstructor() {
        String axl = convert("Planets.java", """
                package util;

                public final class Planets {
                    private Planets() {}

                    public enum Planet {
                        MERCURY(3.303e23, 2.4397e6), EARTH(5.976e24, 6.37814e6);

                        public final double mass;
                        public final double radius;

                        Planet(double mass, double radius) {
                            this.mass = mass;
                            this.radius = radius;
                        }
                    }

                    public enum Level {
                        LOW(1, "low"), HIGH(3, "high");

                        public final int code;
                        public final String label;

                        Level(int code, String label) {
                            this.code = code;
                            this.label = label;
                        }
                    }

                    public static double gravity(Planet p) {
                        return 6.673E-11 * p.mass / (p.radius * p.radius);
                    }
                }
                """);
        assertTrue(axl, axl.contains("\nenum Planet(val mass: Double, val radius: Double) { MERCURY(3.303e23, 2.4397e6), EARTH(5.976e24, 6.37814e6) }\n"));
        assertTrue(axl, axl.contains("\nenum Level(val code: Int, val label: String) { LOW(1, \"low\"), HIGH(3, \"high\") }\n"));
        assertTrue(axl, axl.contains("\nfn gravity(p: Planet) -> Double {\n    return 6.673E-11 * p.mass / (p.radius * p.radius)\n}\n"));
        assertEquals("package util\n\nenum Light(val on: Boolean) { ON(true), OFF(false) }\n", convert("Light.java",
                "package util;\npublic enum Light {\n    ON(true), OFF(false);\n    public final boolean on;\n    Light(boolean on) { this.on = on; }\n}\n"));
    }

    public void testRefusesEnumsWithoutAOneToOneForm() {
        assertRefused("Method.java", "package util;\npublic enum Method {\n    A(1);\n    public final int v;\n    Method(int v) { this.v = v; }\n"
                + "    int twice() { return v * 2; }\n}\n");
        assertRefused("Two.java", "package util;\npublic enum Two {\n    A(1), B;\n    public final int v;\n    Two(int v) { this.v = v; }\n"
                + "    Two() { this.v = 0; }\n}\n");
        assertRefused("Mutable.java", "package util;\npublic enum Mutable {\n    A(1);\n    public int v;\n    Mutable(int v) { this.v = v; }\n}\n");
        assertRefused("Hidden.java", "package util;\npublic enum Hidden {\n    A(1);\n    private final int v;\n    Hidden(int v) { this.v = v; }\n}\n");
        assertRefused("Body.java", "package util;\npublic enum Body {\n    A { };\n}\n");
        assertRefused("Logic.java", "package util;\npublic enum Logic {\n    A(1);\n    public final int v;\n    Logic(int v) { this.v = v * 2; }\n}\n");
        assertRefused("Missing.java", "package util;\npublic enum Missing {\n    A(1), B;\n    public final int v;\n    Missing(int v) { this.v = v; }\n}\n");
        assertRefused("Constant.java", "package util;\npublic enum Constant {\n    A;\n    public static final int MAX = 1;\n}\n");
    }

    public void testConvertsClassesThatExtendAJavaClass() {
        String axl = convert("Errors.java", """
                package util;

                import java.util.LinkedHashMap;
                import java.util.Map;

                public final class Errors {
                    private Errors() {}

                    public static final class NotFound extends RuntimeException {
                        public final String id;

                        public NotFound(String id) {
                            super("not found: " + id);
                            this.id = id;
                        }
                    }

                    public static final class Worker extends Thread {
                        public Worker() {
                            super("worker");
                        }

                        @Override
                        public void run() {
                            System.out.println(getName());
                        }
                    }

                    public static final class Recent extends LinkedHashMap<String, Integer> {
                        public final int limit;

                        public Recent(int limit) {
                            this.limit = limit;
                        }

                        @Override
                        protected boolean removeEldestEntry(Map.Entry<String, Integer> eldest) {
                            return size() > limit;
                        }
                    }

                    public static final class Plain extends Object implements Runnable {
                        public void run() {
                        }
                    }
                }
                """);
        assertTrue(axl, axl.contains("\nclass NotFound(val id: String) : RuntimeException(\"not found: \" + id) {\n}\n"));
        assertTrue(axl, axl.contains("\nclass Worker : Thread(\"worker\") {\n\n    @Override\n    fn run() {\n        println(getName())\n    }\n}\n"));
        assertTrue(axl, axl.contains("\nclass Recent(val limit: Int) : LinkedHashMap<String, Integer>() {\n\n    @Override\n"
                + "    fn removeEldestEntry(eldest: Entry<String, Integer>) -> Boolean {\n        return size() > limit\n    }\n}\n"));
        assertTrue(axl, axl.contains("\nclass Plain : Object(), Runnable {\n\n    fn run() {\n    }\n}\n"));
        assertEquals("package util\n\nclass Loud(val times: Int) : Thread() {\n\n    fn run() {\n        println(times)\n    }\n}\n",
                convert("Loud.java", "package util;\npublic class Loud extends Thread {\n    public final int times;\n"
                        + "    public Loud(int times) {\n        this.times = times;\n    }\n    public void run() {\n"
                        + "        System.out.println(times);\n    }\n}\n"));
    }

    public void testRefusesSuperclassesWithoutAOneToOneForm() {
        assertRefused("Two.java", "package util;\npublic class Two extends Thread {\n    public Two() { super(\"a\"); }\n"
                + "    public Two(String n) { super(n); }\n}\n");
        assertRefused("Later.java", "package util;\npublic class Later extends Thread {\n    public final int n;\n"
                + "    public Later(int n) { this.n = n; super(); }\n}\n");
        assertRefused("Work.java", "package util;\npublic class Work extends Thread {\n    public Work(String n) { super(n); System.out.println(n); }\n}\n");
    }

    public void testConvertsRecordsWithMethodsToDataClassesWithABody() {
        assertEquals("""
                package util

                data class Money(val cents: Long) : Comparable<Money> {
                    @Override
                    fn compareTo(other: Money) -> Int {
                        return Long.compare(cents, other.cents())
                    }

                    fn plus(other: Money) -> Money {
                        return Money(cents + other.cents())
                    }
                }
                """, convert("Money.java", """
                package util;

                public record Money(long cents) implements Comparable<Money> {
                    @Override
                    public int compareTo(Money other) {
                        return Long.compare(cents, other.cents());
                    }

                    public Money plus(Money other) {
                        return new Money(this.cents + other.cents());
                    }
                }
                """));
        assertRefused("Accessor.java", "package util;\npublic record Accessor(int a) {\n    public int a() { return a; }\n}\n");
        assertRefused("Compact.java", "package util;\npublic record Compact(int a) {\n    public Compact { }\n}\n");
        assertRefused("Static.java", "package util;\npublic record Static(int a) {\n    public static Static one() { return new Static(1); }\n}\n");
        assertRefused("Field.java", "package util;\npublic record Field(int a) {\n    static final int MAX = 1;\n}\n");
    }

    public void testConvertsTryWithResourcesToUse() {
        String axl = convert("Readers.java", """
                package util;

                import java.io.IOException;
                import java.io.StringReader;

                public final class Readers {
                    private Readers() {}

                    public static int count(String text) {
                        int n = 0;
                        try (StringReader reader = new StringReader(text)) {
                            n = reader.read();
                        } catch (IOException e) {
                            throw new IllegalStateException(e);
                        }
                        try (var second = new StringReader("y")) {
                            n += second.read();
                        } catch (Exception e) {
                            System.out.println(e.getMessage());
                        }
                        return n;
                    }
                }
                """);
        assertTrue(axl, axl.contains("    use (val reader: StringReader = StringReader(text)) {\n        n = reader.read()\n"
                + "    } else e -> throw IllegalStateException(e)\n"));
        assertTrue(axl, axl.contains("    use (val second: StringReader = StringReader(\"y\")) {\n        n += second.read()\n"
                + "    } else e -> println(e.getMessage())\n    return n\n"));
    }

    public void testRefusesTriesWithoutAOneToOneForm() {
        String open = "java.io.StringReader r = new java.io.StringReader(\"x\")";
        assertRefused("NoCatch.java", inClass("public static void f() { try (" + open + ") { r.read(); } }"));
        assertRefused("Finally.java", inClass("public static void f() { try (" + open + ") { r.read(); } "
                + "catch (Exception e) { System.out.println(e); } finally { System.out.println(1); } }"));
        assertRefused("TwoCatches.java", inClass("public static void f() { try (" + open + ") { r.read(); } "
                + "catch (java.io.IOException e) { System.out.println(e); } catch (RuntimeException e) { System.out.println(e); } }"));
        assertRefused("TwoStatements.java", inClass("public static void f() { try (" + open + ") { r.read(); } "
                + "catch (Exception e) { System.out.println(e); System.out.println(1); } }"));
        assertRefused("TwoResources.java", inClass("public static void f() { try (" + open + "; var s = new java.io.StringReader(\"y\")) "
                + "{ r.read(); } catch (Exception e) { System.out.println(e); } }"));
        assertRefused("Existing.java", inClass("public static void f(java.io.StringReader r) { try (r) { r.read(); } "
                + "catch (Exception e) { System.out.println(e); } }"));
        assertRefused("Throwable.java", inClass("public static void f() { try (" + open + ") { r.read(); } "
                + "catch (Throwable t) { System.out.println(t); } }"));
        assertRefused("PlainTry.java", inClass("public static void f() { try { System.out.println(1); } "
                + "catch (Exception e) { System.out.println(e); } }"));
    }

    public void testConvertsBreakAndContinueWithoutLabels() {
        String axl = convert("Loops.java", inClass("""
                public static int firstNegative(java.util.List<Integer> xs) {
                    int found = 0;
                    for (int x : xs) {
                        if (x >= 0) {
                            continue;
                        }
                        found = x;
                        break;
                    }
                    return found;
                }"""));
        assertTrue(axl, axl.contains("        if (x >= 0) {\n            continue\n        }\n        found = x\n        break\n    }\n    return found\n"));
        assertRefused("Labels.java", inClass("public static void f(java.util.List<Integer> xs) { outer: for (int x : xs) { "
                + "for (int y : xs) { if (y > x) { break outer; } } } }"));
        assertRefused("Continue.java", inClass("public static void f(java.util.List<Integer> xs) { outer: for (int x : xs) { "
                + "for (int y : xs) { continue outer; } } }"));
    }

    /** A class named after its file, which the helpers below fill in for `CLASS`. */
    private static String inClass(String member) {
        return "package util;\npublic final class CLASS {\n" + member + "\n}\n";
    }

    private PsiJavaFile configure(String fileName, String java) {
        String text = java.replace("CLASS", fileName.replace(".java", ""));
        return (PsiJavaFile) myFixture.configureByText(fileName, text);
    }

    private String convert(String fileName, String java) {
        return JavaToAxolotl.convert(configure(fileName, java));
    }

    private void assertRefused(String fileName, String java) {
        assertFalse(fileName, JavaToAxolotl.isConvertible(configure(fileName, java)));
    }
}
