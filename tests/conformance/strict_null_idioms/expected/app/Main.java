package app;
import axl.lang.*;
import org.jspecify.annotations.*;
import java.util.List;

@NullMarked public final class Main {
    private Main() {}
    public static long count(List<String> xs) {
        Nulls.param(xs, "xs");
        return xs.stream().filter(it -> it.isEmpty()).count();
    }
    public static int size(boolean flag) {
        final var home = System.getenv("AXL_SURELY_NOT_SET");
        if (home == null) {
            if (flag) {
                return 1;
            } else {
                return 2;
            }
        }
        return home.length();
    }
    public static int fallback() {
        var home = System.getenv("AXL_SURELY_NOT_SET");
        while (home == null) {
            home = "fallback";
        }
        return home.length();
    }
    public static void main(String[] args) {
        System.out.println(count(Nulls.sure(List.of("a", ""))));
        final var names = java.util.Objects.requireNonNull(List.of("b", "a"));
        System.out.println(Nulls.first(names.stream().sorted(java.util.Comparator.naturalOrder()).map(Nulls.nonNull(it -> it.length())).iterator()));
        System.out.println(size(true));
        System.out.println(fallback());
        final var env = Nulls.<String>or(System.getenv("AXL_SURELY_NOT_SET"), () -> "none");
        System.out.println(env.length());
    }
}
