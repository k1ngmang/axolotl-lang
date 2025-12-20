package axl.lang;
import java.util.Iterator;
import java.util.function.Function;
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
    // As an argument, a Nulls.map call takes its type from the overloads of the method it is passed to,
    // and println(String) and println(char[]) both fit. As the receiver of `.value()` it is typed first.
    public record Typed<T extends @Nullable Object>(@Nullable T value) {}
    public static <T extends @Nullable Object> Typed<T> typed(@Nullable T value) { return new Typed<>(value); }
    public static <T, R> @Nullable R map(@Nullable T a, Function<? super T, ? extends @Nullable R> f) {
        return a == null ? null : f.apply(a);
    }
}
