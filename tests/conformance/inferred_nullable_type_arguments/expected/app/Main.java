package app;
import axl.lang.*;
import org.jspecify.annotations.*;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@NullMarked public final class Main {
    private Main() {}
    public static <M extends Map<String, @Nullable String>> M putIn(M map, @Nullable String value) {
        Nulls.param(map, "map");
        map.put("key", value);
        return map;
    }
    public static Map<String, @Nullable String> toMap(@Nullable String value) {
        final var map = new LinkedHashMap<String, @Nullable String>();
        return putIn(map, value);
    }
    public static Map<String, @Nullable String> toMapDirectly(@Nullable String value) {
        return putIn(new LinkedHashMap<String, @Nullable String>(), value);
    }
    public static List<@Nullable String> column(List<Map<String, @Nullable String>> rows) {
        Nulls.param(rows, "rows");
        return rows.stream().map(row -> row.get("a")).collect(Collectors.toList());
    }
    public static List<@Nullable String> firstColumn(List<Map<String, @Nullable String>> rows) {
        Nulls.param(rows, "rows");
        return rows.stream().map(Nulls.nullable(it -> it.get("a"))).toList();
    }
    public static long countMissing(List<Map<String, @Nullable String>> rows) {
        Nulls.param(rows, "rows");
        final List<@Nullable String> values = rows.stream().map(Nulls.nullable(it -> it.get("a"))).toList();
        return values.stream().filter(it -> it == null).count();
    }
    public static List<String> present(List<Map<String, @Nullable String>> rows) {
        Nulls.param(rows, "rows");
        return Nulls.nonNulls(rows.stream().map(Nulls.nullable(it -> it.get("a"))).filter(it -> it != null)).toList();
    }
    public static void main(String[] args) {
        System.out.println(toMap(null));
        System.out.println(toMapDirectly("v"));
        final List<Map<String, @Nullable String>> rows = List.of(Map.of("a", "x"), Map.of("b", "y"));
        System.out.println(column(rows));
        System.out.println(firstColumn(rows));
        System.out.println(countMissing(rows));
        System.out.println(present(rows));
    }
}
