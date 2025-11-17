package app;
import axl.lang.*;
import org.jspecify.annotations.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@NullMarked public final class Main {
    private Main() {}
    public static @Nullable List<String> cached() {
        return null;
    }
    public static int f(int x) {
        return x + 1;
    }
    public static String f(String x) {
        return x + "!";
    }
    public static final Map<String, String> cfg = Map.of("a", "1");
    public static final @Nullable Integer len = Nulls.map(cfg.get("zz"), $n1 -> $n1.length());
    public static final @Nullable String firstOrNull = Nulls.first(cfg.keySet().stream().iterator());
    public static final @Nullable String fromOther = util.Other.maybe();
    public static final @Nullable String either = Nulls.or(util.Other.maybe(), () -> util.Other.maybe());
    public static final List<String> names = Nulls.or(cached(), () -> new ArrayList<String>());
    public static final String overloaded = f("a");
    public static void main(String[] args) {
        System.out.println(len);
        System.out.println(firstOrNull);
        System.out.println(fromOther);
        System.out.println(either);
        System.out.println(names);
        System.out.println(overloaded);
    }
}
