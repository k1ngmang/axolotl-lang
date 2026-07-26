package app;
import axl.lang.*;
import org.jspecify.annotations.*;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@NullMarked public final class Main {
    private Main() {}
    public static int total(Map<String, List<String>> m, String k) {
        final var xs = Nulls.<List<String>>or(m.get(k), () -> Collections.emptyList());
        var n = 0;
        for (final var x : xs) {
            n += x.length();
        }
        return n;
    }
    public static String firstOr(Map<String, List<String>> m, String k) {
        final var xs = Nulls.<List<String>>or(m.get(k), () -> List.of());
        return (xs.isEmpty() ? "none" : xs.get(0));
    }
    public static void main(String[] args) {
        final var m = new HashMap<String, List<String>>();
        m.put("a", List.of("ab", "cde"));
        System.out.println(total(m, "a"));
        System.out.println(total(m, "b"));
        System.out.println(firstOr(m, "a"));
        System.out.println(firstOr(m, "b"));
    }
}
