package app;
import axl.lang.*;
import org.jspecify.annotations.*;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@NullMarked public final class Main {
    private Main() {}
    public static Map<Class<?>, String> names() {
        final var m = new HashMap<Class<?>, String>();
        m.put(String.class, "text");
        return m;
    }
    public static void main(String[] args) {
        System.out.println(names().get(String.class));
        final var lists = new java.util.ArrayList<List<?>>();
        lists.add(List.of(1, 2));
        final var maps = new HashMap<String, Map<?, ?>>();
        maps.put("a", Map.of(1, "x"));
        System.out.println(lists.get(0).size() + java.util.Objects.requireNonNull(maps.get("a")).size());
    }
}
