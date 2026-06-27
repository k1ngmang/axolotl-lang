package axl.lang;
import java.util.function.Function;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
// Null-marked so that NullAway checks the value type: Ok(null) does not fit Result<String, E>.
@NullMarked
public sealed interface Result<T extends @Nullable Object, E extends @Nullable Object> permits Ok, Err {
    static <T extends @Nullable Object, E extends @Nullable Object> Result<T, E> attempt(ThrowingSupplier<T> x, Function<Exception, E> y) {
        try { return new Ok<>(x.get()); } catch (Exception ex) { return new Err<>(y.apply(ex)); }
    }
    // `attempt X else e -> throw Y`: the value of X, or Y thrown instead of an Err.
    static <T extends @Nullable Object> T orThrow(ThrowingSupplier<T> x, Function<Exception, ? extends RuntimeException> y) {
        try { return x.get(); } catch (Exception ex) { throw y.apply(ex); }
    }
    default boolean isErr() { return this instanceof Err; }
    // Converts the error, so that `try` works where the function returns another error type.
    default <F extends @Nullable Object> Result<T, F> mapErr(Function<? super E, ? extends F> f) {
        if (this instanceof Err<T, E>(var e)) return new Err<>(f.apply(e));
        return new Ok<>(((Ok<T, E>) this).value());
    }
    // for `try`: the value from Ok
    static <T extends @Nullable Object> T unwrap(Result<T, ?> r) {
        if (r instanceof Ok<T, ?>(var v)) return v;
        throw new IllegalStateException("unwrap on Err");
    }
    // for `try`: the same Err with a new T (U is inferred from the function return type)
    static <T extends @Nullable Object, U extends @Nullable Object, E extends @Nullable Object> Result<U, E> propagate(Result<T, E> r) {
        if (r instanceof Err<T, E>(var e)) return new Err<>(e);
        throw new IllegalStateException("propagate on Ok");
    }
}
