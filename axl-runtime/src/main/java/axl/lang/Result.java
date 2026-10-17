package axl.lang;
import java.util.function.Function;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
// Null-marked so that NullAway checks the value type: Ok(null) does not fit Result<String, E>.
@NullMarked
public sealed interface Result<T extends @Nullable Object, E extends @Nullable Object> permits Ok, Err {
    static <T extends @Nullable Object, E extends @Nullable Object> Result<T, E> attempt(ThrowingSupplier<T> x, Function<Exception, E> y) {
        try { return new Ok<>(x.get()); } catch (Exception ex) { keepInterrupt(ex); return new Err<>(y.apply(ex)); }
    }
    // `attempt X else e -> throw Y`: the value of X, or Y thrown instead of an Err.
    static <T extends @Nullable Object> T orThrow(ThrowingSupplier<T> x, Function<Exception, ? extends RuntimeException> y) {
        try { return x.get(); } catch (Exception ex) { keepInterrupt(ex); throw y.apply(ex); }
    }
    // `attempt X else e: IOException -> Y`: only an exception of that class becomes an Err; any other is thrown on.
    static <T extends @Nullable Object, X extends Throwable, E extends @Nullable Object> Result<T, E> attempt(ThrowingCall<T, X> x, Function<? super X, E> y, Class<X> type) {
        try { return new Ok<>(x.get()); } catch (Throwable ex) { return new Err<>(y.apply(caught(ex, type))); }
    }
    static <T extends @Nullable Object, X extends Throwable> T orThrow(ThrowingCall<T, X> x, Function<? super X, ? extends RuntimeException> y, Class<X> type) {
        try { return x.get(); } catch (Throwable ex) { throw y.apply(caught(ex, type)); }
    }
    // Starts the body of the statement `attempt X else e: IOException -> Y`: javac then accepts the catch of a checked
    // exception that X does not declare, as it accepts the value form's ThrowingCall; Axolotl code throws them undeclared.
    static <X extends Throwable> void mayThrow(Class<X> type) throws X { }
    // The exception as the class the handler takes; another one is thrown on as it is.
    private static <X extends Throwable> X caught(Throwable ex, Class<X> type) {
        if (!type.isInstance(ex)) throw Result.<RuntimeException>sneaky(ex);
        keepInterrupt(ex);
        return type.cast(ex);
    }
    // Throws what the handler does not take as it is: the body's ThrowingCall leaves it unchecked or an error.
    @SuppressWarnings("unchecked")
    private static <X extends Throwable> RuntimeException sneaky(Throwable ex) throws X { throw (X) ex; }
    // Catching InterruptedException clears the flag; setting it again lets a loop over isInterrupted() end.
    private static void keepInterrupt(Throwable ex) {
        if (ex instanceof InterruptedException) Thread.currentThread().interrupt();
    }
    default boolean isOk() { return this instanceof Ok; }
    default boolean isErr() { return this instanceof Err; }
    // Converts the value of an Ok and keeps an Err as it is.
    default <U extends @Nullable Object> Result<U, E> map(Function<? super T, ? extends U> f) {
        if (this instanceof Ok<T, E>(var v)) return new Ok<>(f.apply(v));
        return new Err<>(((Err<T, E>) this).error());
    }
    // The value of an Ok, or the fallback for an Err.
    default T getOrElse(T fallback) {
        if (this instanceof Ok<T, E>(var v)) return v;
        return fallback;
    }
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
    // for `r.error()` after `if (r is Ok) { return }`: the error from Err
    static <E extends @Nullable Object> E unwrapError(Result<?, E> r) {
        if (r instanceof Err<?, E>(var e)) return e;
        throw new IllegalStateException("unwrapError on Ok");
    }
    // for `try`: the same Err with a new T (U and E are inferred from the function return type, whose
    // error type may be a supertype of the error, such as a sealed interface the error implements)
    static <T extends @Nullable Object, U extends @Nullable Object, E extends @Nullable Object> Result<U, E> propagate(Result<T, ? extends E> r) {
        if (r instanceof Err<T, ? extends E>(var e)) return new Err<>(e);
        throw new IllegalStateException("propagate on Ok");
    }
}
