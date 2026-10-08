package app;
import axl.lang.*;
import org.jspecify.annotations.*;
import java.util.List;
import java.util.Objects;
import java.util.function.BiPredicate;
import java.util.function.Function;

@NullMarked public final class Main {
    private Main() {}
    public static final class Equalizer<T> {
        private final BiPredicate<T, T> eq;
        public Equalizer(BiPredicate<T, T> eq) {
            this.eq = eq;
        }
        public Equalizer() {
            this(Object::equals);
        }
        public boolean same(T a, T b) {
            return eq.test(a, b);
        }
    }
    public static final class Loose<T extends @Nullable Object> {
        private final BiPredicate<T, T> eq;
        public Loose(BiPredicate<T, T> eq) {
            this.eq = eq;
        }
        public Loose() {
            this((a, b) -> Objects.equals(a, b));
        }
        public boolean same(T a, T b) {
            return eq.test(a, b);
        }
    }
    public static <T> BiPredicate<T, T> viaLambda() {
        return (a, b) -> a.equals(b);
    }
    public static <T> Function<T, String> viaFunction() {
        return a -> a.toString();
    }
    public static <T extends @Nullable Object> Function<T, String> viaSafeCall() {
        return a -> Nulls.or(Nulls.map(a, $n1 -> $n1.toString()), () -> "none");
    }
    public static <T> List<String> names(List<T> items) {
        return items.stream().map(Nulls.nonNull(it -> it.toString())).toList();
    }
    public static void main(String[] args) {
        System.out.println(new Equalizer<String>().same("a", "a"));
        System.out.println(new Loose<@Nullable String>().same(null, "x"));
        final BiPredicate<String, String> differ = viaLambda();
        System.out.println(differ.test("a", "b"));
        final Function<Integer, String> show = viaFunction();
        System.out.println(show.apply(4));
        final Function<@Nullable String, String> maybe = viaSafeCall();
        System.out.println(maybe.apply(null));
        System.out.println(names(List.of(1, 2)));
    }
}
