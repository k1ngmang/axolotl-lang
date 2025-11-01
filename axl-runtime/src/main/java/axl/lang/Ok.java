package axl.lang;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
@NullMarked
public record Ok<T extends @Nullable Object, E extends @Nullable Object>(T value) implements Result<T, E> {}
