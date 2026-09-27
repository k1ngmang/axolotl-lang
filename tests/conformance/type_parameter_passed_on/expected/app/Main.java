package app;
import axl.lang.*;
import org.jspecify.annotations.*;
import java.util.Arrays;
import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;

@NullMarked public final class Main {
    private Main() {}
    public static <T extends @Nullable Object> Conv<T> find(Class<T> clazz) {
        return s -> clazz.cast(s);
    }
    public static <T extends @Nullable Object> Result<T, String> convert(String s, Class<T> clazz) {
        return Result.attempt(() -> find(clazz).apply(s), e -> "bad: " + e.getMessage());
    }
    public static <T extends @Nullable Object> Supplier<T> first(List<T> xs) {
        return () -> xs.get(0);
    }
    public static <T extends @Nullable Object> Supplier<T> kept(T x) {
        final Supplier<T> s = () -> x;
        return s;
    }
    public static <T extends @Nullable Object, U extends @Nullable Object> Function<T, U> second(T x, U u) {
        return t -> u;
    }
    public static <T extends @Nullable Object> Result<T, String> wrapped(T x) {
        return new Ok<>(x);
    }
    public static <T extends @Nullable Object> Result<String, T> failed(T x) {
        return new Err<>(x);
    }
    public static <T extends @Nullable Object> String shown(T x) {
        return Nulls.<String>or(Nulls.map(x, $n1 -> $n1.toString()), () -> "null");
    }
    public static void main(String[] args) {
        System.out.println(convert("a", String.class));
        System.out.println(convert("b", Integer.class) instanceof Err<?, ?>);
        final @Nullable String maybe = null;
        System.out.println(shown(first(Arrays.asList(maybe)).get()));
        System.out.println(first(List.of("c")).get());
        System.out.println(shown(kept(maybe).get()));
        System.out.println(second(1, "d").apply(2));
        System.out.println(wrapped(maybe));
        System.out.println(failed("e"));
    }
}
