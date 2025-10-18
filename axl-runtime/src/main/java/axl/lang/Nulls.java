package axl.lang;
import java.util.function.Function;
import java.util.function.Supplier;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
@NullMarked public final class Nulls {
    private Nulls() {}
    public static <T> T or(@Nullable T a, Supplier<? extends T> b) { return a != null ? a : b.get(); }
    public static <T, R> @Nullable R map(@Nullable T a, Function<? super T, ? extends @Nullable R> f) {
        return a == null ? null : f.apply(a);
    }
}
