package axl.lang;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
@NullMarked
public record Err<T extends @Nullable Object, E extends @Nullable Object>(E error) implements Result<T, E> {}
