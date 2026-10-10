package lib;
import axl.lang.*;
import org.jspecify.annotations.*;

@NullMarked public final class Options {
    private Options() {}
    public sealed interface Option<T extends @Nullable Object> {
        boolean present();
    }
    public record Some<T extends @Nullable Object>(boolean present, T value) implements Option<T> {}
    public record None<T extends @Nullable Object>(boolean present) implements Option<T> {}
    public interface Mapper<T extends @Nullable Object, R extends @Nullable Object> {
        R map(T x);
    }
    public static Option<String> some(String s) {
        Nulls.param(s, "s");
        return new Some<String>(true, s);
    }
}
