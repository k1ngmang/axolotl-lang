package axl.lang;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
// The body of `attempt X else e: IOException -> Y`: Java checks that X throws no other checked exception.
@NullMarked
@FunctionalInterface public interface ThrowingCall<T extends @Nullable Object, X extends Throwable> { T get() throws X; }
