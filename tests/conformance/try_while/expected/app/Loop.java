package app;
import axl.lang.*;
import org.jspecify.annotations.*;

@NullMarked public final class Loop {
    private Loop() {}
    public static Result<Boolean, String> below(int value, int limit) {
        if (value > 5) {
            return new Err<>("too far: " + value);
        }
        return new Ok<>(value < limit);
    }
    public static Result<Integer, String> count(int limit) {
        var i = 0;
        while (true) {
            final var $t1 = below(i, limit);
            if ($t1.isErr()) return Result.propagate($t1);
            if (!(Result.unwrap($t1))) break;
            i += 1;
        }
        final var $t2 = below(i, limit);
        if ($t2.isErr()) return Result.propagate($t2);
        if (Result.unwrap($t2)) {
            return new Err<>("unreachable");
        } else {
            boolean $t3 = i > 2;
            if ($t3) {
                final var $t4 = below(i, 9);
                if ($t4.isErr()) return Result.propagate($t4);
                $t3 = Result.unwrap($t4);
            }
            if ($t3) {
                return new Ok<>(i * 10);
            }
        }
        return new Ok<>(i);
    }
    public static void main(String[] args) {
        System.out.println(count(2));
        System.out.println(count(4));
        System.out.println(count(9));
    }
}
