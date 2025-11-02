package axl.lang;
import java.util.function.Function;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
@NullMarked public final class Nulls {
    private Nulls() {}
    // NullAway does not check what a lambda returns for JDK interfaces such as Supplier, so `a ?: b`
    // with a nullable `b` would pass as non-null; a null-marked interface makes it check.
    @FunctionalInterface public interface Fallback<T extends @Nullable Object> { T get(); }
    public static <T extends @Nullable Object> T or(@Nullable T a, Fallback<T> b) { return a != null ? a : b.get(); }
    // Stream.map takes a JDK Function, whose result NullAway does not check either: a pipe's map
    // stage must not put nulls into a List<T>.
    @FunctionalInterface public interface NonNullFunction<T extends @Nullable Object, R> { R apply(T value); }
    public static <T extends @Nullable Object, R> Function<T, R> nonNull(NonNullFunction<T, R> f) { return f::apply; }
    public static <T, R> @Nullable R map(@Nullable T a, Function<? super T, ? extends @Nullable R> f) {
        return a == null ? null : f.apply(a);
    }
}
