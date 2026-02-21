package app;
import axl.lang.*;
import org.jspecify.annotations.*;
import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.IntUnaryOperator;

@NullMarked public final class Main {
    private Main() {}
    public static void main(String[] args) {
        final Function<Integer, Integer> f = (x) -> x + 1;
        final IntUnaryOperator g = (x) -> x * 3;
        final BiFunction<Integer, @Nullable String, String> h = (Integer n, @Nullable String s) -> "" + n + s;
        System.out.println(f.apply(1));
        System.out.println(g.applyAsInt(2));
        System.out.println(h.apply(3, null));
        List.of(1, 2).stream().map(Nulls.nonNull((x) -> x * 2)).forEach(it -> System.out.println(it));
    }
}
