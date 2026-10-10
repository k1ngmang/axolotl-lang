package app;
import axl.lang.*;
import org.jspecify.annotations.*;
import java.util.List;
import java.util.Map;

@NullMarked public final class Main {
    private Main() {}
    public static int size(List<?> xs) {
        Nulls.param(xs, "xs");
        return xs.size();
    }
    public static void main(String[] args) {
        final Class<?> c = String.class;
        System.out.println(c.getSimpleName() + " " + size(List.of(1, 2)));
        final Map<String, ?> m = Map.of("a", 1);
        System.out.println(m);
        final List<List<?>> nested = List.of(List.of("x"), List.of(2));
        System.out.println(nested);
    }
}
