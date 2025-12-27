package app;
import axl.lang.*;
import org.jspecify.annotations.*;
import java.util.List;

@NullMarked public final class Main {
    private Main() {}
    public static void main(String[] args) {
        final @Nullable String s = null;
        List.of(1, 2).stream().forEach(it -> {
            var n = it;
            n += 10;
            final var $c1 = n;
            System.out.println(Nulls.or(s, () -> "n=" + $c1));
        });
        System.out.println(lengths());
        System.out.println(nested().get());
    }
    public static java.util.List<Integer> lengths() {
        final @Nullable String s = null;
        return List.of(1, 2).stream().map(Nulls.nonNull(x -> {
            var n = x;
            n += 10;
            final var $c1 = n;
            return Nulls.or(Nulls.map(s, $n1 -> $n1.length()), () -> $c1);
        })).toList();
    }
    public static java.util.function.@Nullable Supplier<String> given() {
        return null;
    }
    public static java.util.function.Supplier<String> nested() {
        var n = 1;
        n += 1;
        final @Nullable String s = null;
        final var $c1 = n;
        return Nulls.or(given(), () -> () -> {
            final var k = Nulls.or(s, () -> "v" + $c1);
            return k;
        });
    }
}
