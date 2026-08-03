package app;
import axl.lang.*;
import org.jspecify.annotations.*;
import java.util.List;
import java.util.Map;

@NullMarked public final class Main {
    private Main() {}
    public static List<String> rest(List<String> xs) {
        final List<String> items = (xs.isEmpty() ? List.of() : xs.subList(1, xs.size()));
        return items;
    }
    public static Map<String, Integer> index(List<String> xs) {
        final Map<String, Integer> byName = Map.of("a", 1);
        final Map<String, Integer> result = (xs.isEmpty() ? Map.of() : byName);
        return result;
    }
    public static void main(String[] args) {
        System.out.println(rest(List.of("a", "b", "c")));
        System.out.println(rest(List.of()));
        System.out.println(index(List.of("a")));
        System.out.println(index(List.of()));
    }
}
