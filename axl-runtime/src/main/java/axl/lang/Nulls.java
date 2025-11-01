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
    public static <T, R> @Nullable R map(@Nullable T a, Function<? super T, ? extends @Nullable R> f) {
        return a == null ? null : f.apply(a);
    }
}
