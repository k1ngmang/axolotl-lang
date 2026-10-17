package app;
import axl.lang.*;
import org.jspecify.annotations.*;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

@NullMarked public final class Main {
    private Main() {}
    public static final List<Map<String, @Nullable String>> ROWS = List.of(Map.of("a", "x"), Map.of("b", "y"), Map.of("a", "zz"));
    public static final List<@Nullable String> COLUMN = ROWS.stream().map(Nulls.nullable(it -> it.get("a"))).toList();
    public static final class Table {
        public final List<Map<String, @Nullable String>> rows;
        public final List<@Nullable String> column;
        public Table(List<Map<String, @Nullable String>> rows) {
            Nulls.param(rows, "rows");
            this.rows = rows;
            this.column = rows.stream().map(Nulls.nullable(it -> it.get("a"))).toList();
        }
    }
    public static List<@Nullable String> column(List<Map<String, @Nullable String>> rows) {
        Nulls.param(rows, "rows");
        return rows.stream().map(Nulls.nullable(it -> it.get("a"))).toList();
    }
    public static int totalLength(List<Map<String, @Nullable String>> rows) {
        Nulls.param(rows, "rows");
        final var values = rows.stream().map(Nulls.asGiven(it -> it.get("a"))).toList();
        var total = 0;
        for (final var v : values) {
            if (v != null) {
                total += v.length();
            }
        }
        return total;
    }
    public static long counted(List<Map<String, @Nullable String>> rows) {
        Nulls.param(rows, "rows");
        return rows.stream().map(Nulls.asGiven(it -> it.get("a"))).count();
    }
    public static long missing(List<Map<String, @Nullable String>> rows) {
        Nulls.param(rows, "rows");
        return rows.stream().map(Nulls.asGiven(it -> it.get("a"))).filter(it -> it == null).count();
    }
    public static @Nullable String firstValue(List<Map<String, @Nullable String>> rows) {
        Nulls.param(rows, "rows");
        return Nulls.first(rows.stream().map(Nulls.asGiven(it -> it.get("a"))).iterator());
    }
    public static List<@Nullable String> sortedValues(List<Map<String, @Nullable String>> rows, Comparator<@Nullable String> order) {
        Nulls.param(rows, "rows");
        Nulls.param(order, "order");
        return rows.stream().map(Nulls.nullable(it -> it.get("a"))).sorted(order).toList();
    }
    public static List<String> orDash(List<Map<String, @Nullable String>> rows) {
        Nulls.param(rows, "rows");
        return rows.stream().map(Nulls.asGiven(it -> it.get("a"))).map(Nulls.nonNull(it -> Nulls.or(it, () -> "-"))).toList();
    }
    public static List<Integer> lengths(List<Map<String, @Nullable String>> rows) {
        Nulls.param(rows, "rows");
        return rows.stream().map(Nulls.asGiven(it -> it.get("a"))).map(Nulls.nonNull(it -> Nulls.or(Nulls.map(it, $n1 -> $n1.length()), () -> 0))).toList();
    }
    public static void main(String[] args) {
        System.out.println(COLUMN);
        System.out.println(new Table(ROWS).column);
        System.out.println(column(ROWS));
        System.out.println(totalLength(ROWS));
        System.out.println(counted(ROWS));
        System.out.println(missing(ROWS));
        System.out.println(firstValue(ROWS));
        System.out.println(sortedValues(ROWS, Comparator.nullsFirst(Comparator.naturalOrder())));
        System.out.println(orDash(ROWS));
        System.out.println(lengths(ROWS));
    }
}
