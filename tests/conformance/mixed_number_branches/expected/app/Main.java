package app;
import axl.lang.*;
import org.jspecify.annotations.*;

@NullMarked public final class Main {
    private Main() {}
    public static @Nullable Long pick(boolean c, @Nullable Integer a, long b) {
        return (c ? Nulls.toLong(a) : Long.valueOf(b));
    }
    public static @Nullable Long both(boolean c, @Nullable Integer a, @Nullable Long b) {
        return (c ? Nulls.toLong(a) : b);
    }
    public static void ratio(int n, @Nullable Integer a) {
        final var r = switch (n) {
            case 1 -> Nulls.toDouble(a);
            default -> Double.valueOf(2.5);
        };
        System.out.println(r);
    }
    public static void main(String[] args) {
        final @Nullable Integer a = null;
        System.out.println(pick(true, a, 5L));
        System.out.println(pick(false, a, 5L));
        System.out.println(pick(true, 3, 5L));
        System.out.println(both(true, null, 5L));
        System.out.println(both(false, 1, null));
        ratio(1, null);
        ratio(1, 4);
        ratio(2, null);
        unknown(true, null);
        unknown(false, "ab");
    }
    public static void unknown(boolean c, @Nullable String s) {
        final var r = (c ? Nulls.toLong(Nulls.map(s, $n1 -> $n1.length())) : Long.valueOf(5L));
        System.out.println(r);
        final var counts = new java.util.HashMap<String, Integer>();
        final var $e1 = c;
        final var d = switch ($e1 == true ? 0 : -1) {
            case 0 -> Nulls.toDouble(counts.get("a"));
            default -> Double.valueOf(2.5);
        };
        System.out.println(d);
    }
}
