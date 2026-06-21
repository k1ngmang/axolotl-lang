package axl.lang;
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
    // A `when` subject of a type the translator does not know: an int becomes an Integer, which a
    // switch with `case null` takes.
    public static <T extends @Nullable Object> T box(T a) { return a; }
    // Stream.map takes a JDK Function, whose result NullAway does not check either: a pipe's map
    // stage must not put nulls into a List<T>.
    @FunctionalInterface public interface NonNullFunction<T extends @Nullable Object, R> { R apply(T value); }
    public static <T extends @Nullable Object, R> Function<T, R> nonNull(NonNullFunction<T, R> f) { return f::apply; }
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
}
