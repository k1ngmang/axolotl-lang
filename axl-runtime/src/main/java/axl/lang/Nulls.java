package axl.lang;
import java.lang.reflect.Array;
import java.util.Arrays;
import java.util.Collection;
import java.util.Iterator;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Stream;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
@NullMarked public final class Nulls {
    private Nulls() {}
    // NullAway does not check what a lambda returns for JDK interfaces such as Supplier, so `a ?: b`
    // with a nullable `b` would pass as non-null; a null-marked interface makes it check.
    @FunctionalInterface public interface Fallback<T extends @Nullable Object> { T get(); }
    public static <T extends @Nullable Object> T or(@Nullable T a, Fallback<T> b) { return a != null ? a : b.get(); }
    // `a ?: 0` with a Long or Double `a`: a lambda returning the int 0 would not fit their wrapper.
    public static int or(@Nullable Integer a, int b) { return a != null ? a : b; }
    public static long or(@Nullable Long a, long b) { return a != null ? a : b; }
    public static double or(@Nullable Double a, double b) { return a != null ? a : b; }
    // `n ?: 0` with a primitive `n`: without these, boxing it would fit both the overloads above and below.
    public static int or(int a, int b) { return a; }
    public static long or(long a, long b) { return a; }
    public static double or(double a, double b) { return a; }
    // `a ?: 0` with a Number or Object `a`, which none of the above takes.
    public static <T extends @Nullable Object> T or(@Nullable T a, T b) { return a != null ? a : b; }
    // `a ?: b!!`: the fallback's `!!`, which NullAway types as not null, unlike requireNonNull there.
    public static <T> T sure(@Nullable T value) { return java.util.Objects.requireNonNull(value); }
    // A non-null parameter of code Java can call: a Java caller's null fails on entry, naming the
    // parameter, instead of deep inside code that trusted the type. It gives the value for `this(...)`
    // and `super(...)` arguments, before which Java 21 allows no statement.
    public static <T> T param(@Nullable T value, String name) {
        if (value == null) {
            throw nullParameter(name);
        }
        return value;
    }
    // The method is read from the stack only when the check fails, which keeps the call short; the
    // trace then starts at that method, as if it had thrown.
    private static NullPointerException nullParameter(String name) {
        StackTraceElement[] trace = new Throwable().getStackTrace();
        int first = 0;
        while (first < trace.length - 1 && trace[first].getClassName().equals(Nulls.class.getName())) {
            first++;
        }
        var e = new NullPointerException("parameter " + name + " of " + methodName(trace[first]) + " must not be null");
        e.setStackTrace(Arrays.copyOfRange(trace, first, trace.length));
        return e;
    }
    private static String methodName(StackTraceElement frame) {
        String className = frame.getClassName();
        String simple = className.substring(Math.max(className.lastIndexOf('.'), className.lastIndexOf('$')) + 1);
        return frame.getMethodName().equals("<init>") ? "the " + simple + " constructor" : simple + "." + frame.getMethodName();
    }
    // `(a ?: f()) == (b ?: g())` whose types the translator does not know: both may be boxed, and `==`
    // compares numbers by value, as Axolotl does for Int, and other objects by reference.
    public static boolean same(@Nullable Object a, @Nullable Object b) {
        if (a instanceof Number x && b instanceof Number y) {
            return isFloating(x) || isFloating(y) ? x.doubleValue() == y.doubleValue() : x.longValue() == y.longValue();
        }
        return a instanceof Boolean || a instanceof Character ? a.equals(b) : a == b;
    }
    private static boolean isFloating(Number n) { return n instanceof Double || n instanceof Float; }
    // `c ? a : b` with an Integer `a` and a Long `b` unboxes both; converting `a` first keeps a null.
    @Contract("!null -> !null")
    public static @Nullable Long toLong(@Nullable Number a) { return a == null ? null : a.longValue(); }
    @Contract("!null -> !null")
    public static @Nullable Double toDouble(@Nullable Number a) { return a == null ? null : a.doubleValue(); }
    // `Character(code)`: Java's `(char) code` keeps the low 16 bits of any number, so a code out of range, such as the -1
    // of Reader.read() at the end, would become another character.
    public static char toChar(int code) {
        if (code < Character.MIN_VALUE || code > Character.MAX_VALUE) {
            throw new IllegalArgumentException("not a character code: " + code);
        }
        return (char) code;
    }
    // A `when` subject of a type the translator does not know: an int becomes an Integer, which a
    // switch with `case null` takes.
    public static <T extends @Nullable Object> T box(T a) { return a; }
    // Stream.map takes a JDK Function, whose result NullAway does not check either: a pipe's map
    // stage must not put nulls into a List<T>.
    @FunctionalInterface public interface NonNullFunction<T extends @Nullable Object, R> { R apply(T value); }
    public static <T extends @Nullable Object, R> Function<T, R> nonNull(NonNullFunction<T, R> f) { return f::apply; }
    // A `map` whose next stage is `filter { it != null }`, which drops the nulls it produces, or whose
    // list goes to a type written with `?`.
    @FunctionalInterface public interface NullableFunction<T extends @Nullable Object, R> { @Nullable R apply(T value); }
    public static <T extends @Nullable Object, R> Function<T, @Nullable R> nullable(NullableFunction<T, R> f) { return f::apply; }
    // A `map` whose elements nothing requires to be non-null: the javac plugin takes them as nullable
    // only where the lambda gives null, and checks them where they are used.
    public static <T extends @Nullable Object, R> Function<T, @Nullable R> asGiven(NullableFunction<T, R> f) { return f::apply; }
    // findFirst throws on a null element; a pipe's `first` gives null for it as for an empty pipe.
    // It takes the stream's iterator, not the stream: the argument then has its type before Java picks
    // an overload for the call around `first`, such as println(char[]) or println(String).
    public static <T extends @Nullable Object> @Nullable T first(Iterator<T> items) {
        return items.hasNext() ? items.next() : null;
    }
    // Wraps the stream of `|> filter { it != null }`: NullAway keeps the `?` of the elements through
    // Stream.filter, so the result could not be stored as a List<String>.
    public static <T> Stream<T> nonNulls(Stream<? extends @Nullable T> items) {
        return items.filter(Objects::nonNull).<T>map(item -> item);
    }
    // As an argument, a Nulls.map call takes its type from the overloads of the method it is passed to,
    // and println(String) and println(char[]) both fit. As the receiver of `.value()` it is typed first.
    public record Typed<T extends @Nullable Object>(@Nullable T value) {}
    public static <T extends @Nullable Object> Typed<T> typed(@Nullable T value) { return new Typed<>(value); }
    // An `if` value with a `?:` branch is typed the same way; it may be null only when a branch may be.
    public record Inferred<T extends @Nullable Object>(T value) {}
    public static <T extends @Nullable Object> Inferred<T> inferred(T value) { return new Inferred<>(value); }
    public record Present<T>(T value) {}
    public static <T> Present<T> present(T value) { return new Present<>(value); }
    public static <T, R> @Nullable R map(@Nullable T a, Function<? super T, ? extends @Nullable R> f) {
        return a == null ? null : f.apply(a);
    }
    // The source of a pipe whose type the translator does not know, such as `bag.items.stream()`:
    // Java picks the overload, so a stream is not streamed again.
    public static <T extends @Nullable Object> Stream<T> stream(Collection<T> items) { return items.stream(); }
    public static <T extends @Nullable Object> Stream<T> stream(Stream<T> items) { return items; }
    // A list passed where Java takes an array of a generic class, `Map.ofEntries(entries)`: unlike
    // `entries.toArray(new Map.Entry[0])`, the array gets its type arguments from the list.
    @SuppressWarnings("unchecked")
    public static <T> T[] arrayOf(Collection<? extends T> items, Class<?> element) {
        return items.toArray((T[]) Array.newInstance(element, 0));
    }
}
