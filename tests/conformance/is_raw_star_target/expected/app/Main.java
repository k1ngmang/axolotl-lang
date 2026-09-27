package app;
import axl.lang.*;
import org.jspecify.annotations.*;
import java.util.Map;
import java.util.function.Function;

@NullMarked public final class Main {
    private Main() {}
    public record Box<T extends @Nullable Object>(T item) {}
    public static @Nullable Class<?> asClass(Object o) {
        if (o instanceof Class<?> o$1) {
            return o$1;
        }
        return null;
    }
    public static @Nullable Class<?> asStarClass(Object o) {
        if (o instanceof Class<?> o$1) {
            return o$1;
        }
        return null;
    }
    public static String describe(Object o) {
        if (o instanceof Box<?> o$1) {
            final Box<?> box = o$1;
            return "box of " + box.item();
        }
        if (o instanceof Map.Entry<?, ?> o$2) {
            final Map.Entry<?, ?> entry = o$2;
            return "entry " + entry.getKey();
        }
        if (o instanceof java.util.Map<?, ?> o$3) {
            final Map<?, ?> map = o$3;
            return "map of " + map.size();
        }
        if (o instanceof Function<?, ?> o$4) {
            final Function<?, ?> f = o$4;
            return "function " + f.equals(o$4);
        }
        return "other";
    }
    public static void main(String[] args) {
        final var $t1 = Nulls.map(asClass(String.class), $n1 -> $n1.getSimpleName());
        System.out.println($t1);
        System.out.println(asStarClass("x") == null);
        System.out.println(describe(new Box<String>("a")));
        System.out.println(describe(Map.entry("k", 1)));
        System.out.println(describe(Map.of(1, 2)));
        final Function<String, String> id = Function.identity();
        System.out.println(describe(id));
    }
}
