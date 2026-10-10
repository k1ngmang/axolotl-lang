package app;
import axl.lang.*;
import org.jspecify.annotations.*;

@NullMarked public final class Model {
    private Model() {}
    public record Order(int id) {}
    public interface Option<T extends @Nullable Object> {}
    public record Some<T extends @Nullable Object>(T value) implements Option<T> {}
    public record Missing<T extends @Nullable Object>(String reason) implements Option<T> {
        public Missing {
            Nulls.param(reason, "reason");
        }
    }
}
