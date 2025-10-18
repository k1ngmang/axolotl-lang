package axl.lang;
import java.util.function.Function;
public sealed interface Result<T, E> permits Ok, Err {
    static <T, E> Result<T, E> attempt(ThrowingSupplier<T> x, Function<Exception, E> y) {
        try { return new Ok<>(x.get()); } catch (Exception ex) { return new Err<>(y.apply(ex)); }
    }
    default boolean isErr() { return this instanceof Err; }
    // for `try`: the value from Ok
    static <T, E> T unwrap(Result<T, E> r) {
        if (r instanceof Ok<T, E>(var v)) return v;
        throw new IllegalStateException("unwrap on Err");
    }
    // for `try`: the same Err with a new T (U is inferred from the function return type)
    static <T, U, E> Result<U, E> propagate(Result<T, E> r) {
        if (r instanceof Err<T, E>(var e)) return new Err<>(e);
        throw new IllegalStateException("propagate on Ok");
    }
}
