package app;
import axl.lang.*;
import org.jspecify.annotations.*;

@NullMarked public final class Counter {
    private Counter() {}
    public static @Nullable String label(int n) {
        return if0(n);
    }
    private static @Nullable String if0(int n) {
        if (n == 0) {
            return null;
        }
        return "n" + n;
    }
    public static void main(String[] args) {
        var count = 0;
        var fallback = "none";
        while (count < 3) {
            final var $c1 = fallback;
            final var $c2 = count;
            System.out.println(Nulls.or(label(count), () -> $c1 + $c2));
            count += 1;
        }
        fallback = "zero";
        final @Nullable String text = null;
        final var $c3 = fallback;
        final var shown = Nulls.or(Nulls.map(text, $n1 -> $n1.concat($c3)), () -> $c3);
        System.out.println(shown);
        final var $c4 = fallback;
        if (Nulls.<String>or(label(0), () -> $c4).equals("zero")) {
            System.out.println("matched");
        }
    }
}
