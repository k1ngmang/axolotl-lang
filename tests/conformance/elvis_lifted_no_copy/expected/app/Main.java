package app;
import axl.lang.*;
import org.jspecify.annotations.*;

@NullMarked public final class Main {
    private Main() {}
    public static String show(@Nullable String a, int n) {
        return a + n;
    }
    public static void main(String[] args) {
        var count = 0;
        count += 1;
        final @Nullable String s = null;
        final @Nullable String t = "t";
        var $t1 = s;
        if (java.util.Objects.isNull($t1)) {
            final var $t2 = Nulls.map(t, $n1 -> $n1.trim());
            $t1 = show($t2, count);
        }
        final var g = $t1;
        System.out.println(g);
    }
}
