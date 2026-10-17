package app;
import axl.lang.*;
import org.jspecify.annotations.*;
import java.util.concurrent.ThreadLocalRandom;

@NullMarked public final class Main {
    private Main() {}
    public static void main(String[] args) {
        final var $t1 = Nulls.bound(ThreadLocalRandom.current().nextInt(2, 3));
        for (var i = Math.min(0, $t1); i < $t1; i++) {
            System.out.println(i);
        }
        final var $t2 = Nulls.bound(ThreadLocalRandom.current().nextLong(60, 61));
        final var $t3 = $t2 - Math.floorMod(Math.floorMod($t2, 20) - Math.floorMod(1, 20), 20);
        boolean $t4 = 1 <= $t2;
        for (var i = Math.min(1, $t3); $t4; $t4 = i != $t3, i += 20) {
            System.out.println(i + Long.MAX_VALUE - 41);
        }
    }
}
