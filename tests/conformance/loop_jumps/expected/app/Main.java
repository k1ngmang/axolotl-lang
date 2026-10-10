package app;
import axl.lang.*;
import org.jspecify.annotations.*;
import java.util.List;

@NullMarked public final class Main {
    private Main() {}
    public static @Nullable Integer firstNegative(List<Integer> xs) {
        Nulls.param(xs, "xs");
        @Nullable Integer found = null;
        for (final var x : xs) {
            if (x >= 0) {
                continue;
            }
            found = x;
            break;
        }
        return found;
    }
    public static String digitsBefore(String s, Character stop) {
        Nulls.param(s, "s");
        Nulls.param(stop, "stop");
        final var out = new StringBuilder();
        var i = 0;
        while (i < s.length()) {
            final var c = s.charAt(i);
            i += 1;
            if (Nulls.same(c, stop)) {
                break;
            }
            if (!Character.isDigit(c)) {
                continue;
            }
            out.append(c);
        }
        return out.toString();
    }
    public static void main(String[] args) {
        System.out.println(firstNegative(List.of(3, -1, -2)));
        System.out.println(firstNegative(List.of(1, 2)));
        System.out.println(digitsBefore("a1b2;c3", ';'));
        var n = 0;
        while (true) {
            n += 1;
            if (n > 3) {
                break;
            }
        }
        System.out.println(n);
    }
}
