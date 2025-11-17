package app;
import axl.lang.*;
import org.jspecify.annotations.*;
import java.util.ArrayList;
import java.util.List;

@NullMarked public final class Main {
    private Main() {}
    public static List<@Nullable String> items() {
        final var xs = new ArrayList<@Nullable String>();
        xs.add(null);
        return xs;
    }
    public static final List<@Nullable String> all = items();
    public static final @Nullable String second = all.get(0);
    public static final @Nullable String copy = second;
    public static final List<String> mapped = all.stream().map(Nulls.nonNull(it -> Nulls.or(it, () -> "x"))).toList();
    public static void main(String[] args) {
        final var $t1 = Nulls.map(second, $n1 -> $n1.length());
        System.out.println($t1);
    }
}
