package app;
import axl.lang.*;
import org.jspecify.annotations.*;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

@NullMarked public final class Main {
    private Main() {}
    public static final AtomicInteger calls = new AtomicInteger();
    public static int counted(int n) {
        calls.incrementAndGet();
        return n;
    }
    public static String upTo(List<String> xs) {
        final var sb = new StringBuilder();
        for (int i = 0, $t1 = xs.size(); i < $t1; i++) {
            sb.append(i).append(xs.get(i));
        }
        for (int i = 0, $t2 = counted(3); i < $t2; i++) {
            sb.append(i);
        }
        return sb.toString();
    }
    public static String diagonals(int d) {
        final var sb = new StringBuilder();
        final int $t1 = -d;
        final int $t2 = d - Math.floorMod(Math.floorMod(d, 2) - Math.floorMod($t1, 2), 2);
        boolean $t3 = $t1 <= d;
        for (int k = $t1; $t3; $t3 = k != $t2, k += 2) {
            sb.append(" ").append(k);
        }
        return sb.toString();
    }
    public static String backwards(int n) {
        final var sb = new StringBuilder();
        for (int i = n - 1; i >= 0; i -= 2) {
            sb.append(" ").append(i);
        }
        final int $t1 = n - 2;
        boolean $t2 = n >= $t1;
        for (int i = n; $t2; $t2 = i != $t1, i--) {
            sb.append(" ").append(i);
        }
        return sb.toString();
    }
    public static void limits() {
        var count = 0;
        final var $t1 = Integer.MAX_VALUE - 2;
        final var $t2 = Integer.MAX_VALUE;
        boolean $t3 = $t1 <= $t2;
        for (var i = Math.min($t1, $t2); $t3; $t3 = i != $t2, i++) {
            count += 1;
        }
        final var $t4 = Integer.MIN_VALUE + 1;
        final var $t5 = Integer.MIN_VALUE;
        boolean $t6 = $t4 >= $t5;
        for (var i = Math.max($t4, $t5); $t6; $t6 = i != $t5, i--) {
            count += 1;
        }
        final var $t7 = Integer.MAX_VALUE - 5;
        final var $t8 = Integer.MAX_VALUE;
        final var $t9 = $t8 - 1 - Math.floorMod(Math.floorMod($t8 - 1, 2) - Math.floorMod($t7, 2), 2);
        boolean $t10 = $t7 < $t8;
        for (var i = Math.min($t7, $t9); $t10; $t10 = i != $t9, i += 2) {
            count += 1;
        }
        final var $t11 = Integer.MAX_VALUE - 6;
        final var $t12 = Integer.MAX_VALUE;
        final var $t13 = $t12 - Math.floorMod(Math.floorMod($t12, 3) - Math.floorMod($t11, 3), 3);
        boolean $t14 = $t11 <= $t12;
        for (var i = Math.min($t11, $t13); $t14; $t14 = i != $t13, i += 3) {
            if (i == Integer.MAX_VALUE - 3) {
                continue;
            }
            count += 1;
        }
        boolean $t15 = true;
        for (int i = 2147483645; $t15; $t15 = i != 2147483647, i++) {
            count += 1;
        }
        System.out.println("at the limits: " + count);
        final var longs = new StringBuilder();
        final var $t16 = Long.MAX_VALUE - 1;
        final var $t17 = Long.MAX_VALUE;
        boolean $t18 = $t16 <= $t17;
        for (var i = Math.min($t16, $t17); $t18; $t18 = i != $t17, i++) {
            longs.append(" ").append(i - Long.MAX_VALUE);
        }
        final long $t19 = 9223372036854775807L - Math.floorMod(Math.floorMod(9223372036854775807L, 4) - Math.floorMod(9223372036854775800L, 4), 4);
        boolean $t20 = true;
        for (long i = 9223372036854775800L; $t20; $t20 = i != $t19, i += 4) {
            longs.append(" ").append(i - Long.MAX_VALUE);
        }
        System.out.println("long ends:" + longs);
    }
    public static void longRange() {
        var sum = 0L;
        for (long i = 0; i < 3L; i++) {
            sum += i;
        }
        final var big = 3000000000L;
        final long $t1 = big + 2;
        boolean $t2 = big <= $t1;
        for (long i = big; $t2; $t2 = i != $t1, i++) {
            sum += i;
        }
        final var start = Duration.ofSeconds(4000000000L);
        final var $t3 = start.toSeconds();
        final var $t4 = start.toSeconds() + 2;
        for (var i = Math.min($t3, $t4); i < $t4; i++) {
            sum += i;
        }
        System.out.println("long sum: " + sum);
        final var days = new StringBuilder();
        final var $t5 = LocalDate.of(2024, 2, 3).getDayOfMonth();
        boolean $t6 = 1 <= $t5;
        for (var day = Math.min(1, $t5); $t6; $t6 = day != $t5, day++) {
            days.append(day);
        }
        System.out.println("days: " + days);
    }
    public static String steps(int step) {
        final var sb = new StringBuilder();
        if (step <= 0) throw new IllegalArgumentException("Step must be positive, was: " + step + ".");
        final int $t1 = 10 - Math.floorMod(Math.floorMod(10, step) - Math.floorMod(0, step), step);
        boolean $t2 = true;
        for (int i = 0; $t2; $t2 = i != $t1, i += step) {
            sb.append(" ").append(i);
        }
        return sb.toString();
    }
    public static void emptyRanges() {
        var runs = 0;
        for (int i = 5; i < 5; i++) {
            runs += 1;
        }
        for (int i = 3; i <= 1; i++) {
            runs += 1;
        }
        for (int i = 1; i >= 3; i--) {
            runs += 1;
        }
        final var n = 0;
        final int $t1 = n - Math.floorMod(Math.floorMod(n, 2) - Math.floorMod(1, 2), 2);
        boolean $t2 = 1 <= n;
        for (int i = 1; $t2; $t2 = i != $t1, i += 2) {
            runs += 1;
        }
        System.out.println("empty ranges ran " + runs + " times");
        final var until = 2;
        final var downTo = 1;
        var sum = 0;
        boolean $t3 = until >= downTo;
        for (int i = until; $t3; $t3 = i != downTo, i--) {
            sum += i;
        }
        System.out.println("2 downTo 1 adds up to " + sum);
    }
    public static String jumps() {
        final var sb = new StringBuilder();
        loop$1: for (int i = 1; i <= 20; i++) {
            if (i % 2 == 0) {
                continue;
            }
            switch (i) {
                case 9 -> {
                    break loop$1;
                }
                default -> {
                    sb.append(i);
                }
            }
        }
        var limit = 4;
        for (int i = 0, $t1 = limit; i < $t1; i++) {
            limit -= 1;
            sb.append("-").append(i);
        }
        return sb.toString();
    }
    public static int nested(int n) {
        var pairs = 0;
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                pairs += 1;
            }
        }
        return pairs;
    }
    public static List<Integer> captured() {
        final var suppliers = new ArrayList<Supplier<Integer>>();
        for (int i$1 = 1; i$1 <= 3; i$1++) {
            final int i = i$1;
            suppliers.add(() -> i * 10);
        }
        return suppliers.stream().map(Nulls.nonNull(s -> s.get())).toList();
    }
    public static String repeated() {
        final var sb = new StringBuilder();
        for (int $unused1 = 1; $unused1 <= 3; $unused1++) {
            sb.append("x");
        }
        return sb.toString();
    }
    public static void main(String[] args) {
        System.out.println(upTo(List.of("a", "b")) + ", end read " + calls.get() + " time");
        System.out.println("diagonals:" + diagonals(3));
        System.out.println("backwards:" + backwards(6));
        limits();
        longRange();
        System.out.println("steps of 4:" + steps(4));
        System.out.println(Result.attempt(() -> steps(0), e -> "" + e.getMessage()));
        System.out.println(Result.attempt(() -> steps(-1), e -> "" + e.getMessage()));
        emptyRanges();
        System.out.println(jumps());
        System.out.println("pairs: " + nested(4));
        System.out.println(captured());
        System.out.println(repeated());
    }
}
