package app;
import axl.lang.*;
import org.jspecify.annotations.*;
import java.util.HashMap;

@NullMarked public final class Main {
    private Main() {}
    public static void main(String[] args) {
        final var counts = new HashMap<String, Integer>();
        System.out.println(Nulls.or(counts.get("a"), () -> 2.5));
        System.out.println(Nulls.or(counts.get("a"), () -> 5L));
        final var longs = new HashMap<String, Long>();
        longs.put("five", 5L);
        final var $e1 = longs.get("b");
        final var $t1 = switch (java.util.Objects.isNull($e1) ? -1 : $e1 == 5L ? 0 : -1) {
            case 0 -> "five";
            default -> "other";
        };
        System.out.println($t1);
        final var $e2 = longs.get("five");
        final var $t2 = switch (java.util.Objects.isNull($e2) ? -1 : $e2 == 5L ? 0 : -1) {
            case 0 -> "five";
            default -> "other";
        };
        System.out.println($t2);
        final var flags = new HashMap<String, Boolean>();
        final var $e3 = flags.get("b");
        final var $t3 = switch (java.util.Objects.isNull($e3) ? -1 : $e3 == true ? 0 : -1) {
            case 0 -> "t";
            default -> "other";
        };
        System.out.println($t3);
    }
}
