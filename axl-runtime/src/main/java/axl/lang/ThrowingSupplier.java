package axl.lang;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
@NullMarked
@FunctionalInterface public interface ThrowingSupplier<T extends @Nullable Object> { T get() throws Exception; }
