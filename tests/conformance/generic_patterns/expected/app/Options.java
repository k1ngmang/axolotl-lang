package app;
import axl.lang.*;
import org.jspecify.annotations.*;

@NullMarked public final class Options {
    private Options() {}
    public sealed interface Option<T extends @Nullable Object> {
        boolean present();
    }
    public record Some<T extends @Nullable Object>(boolean present, T value) implements Option<T> {}
    public record None<T extends @Nullable Object>(boolean present) implements Option<T> {}
    public static Option<Integer> parse(String s) {
        Nulls.param(s, "s");
        final var n = Result.attempt(() -> Integer.parseInt(s), $ex1 -> -1);
        switch (n) {
            case Ok(var n$1) -> {
                return new Some<Integer>(true, n$1);
            }
            case Err(var n$2) -> {
                return new None<Integer>(false);
            }
        }
    }
}
