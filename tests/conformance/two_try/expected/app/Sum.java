package app;
import axl.lang.*;
import org.jspecify.annotations.*;

@NullMarked public final class Sum {
    private Sum() {}
    public static Result<Integer, String> parse(String s) {
        Nulls.param(s, "s");
        return Result.attempt(() -> Integer.parseInt(s), $ex1 -> "bad: " + s);
    }
    public static Result<Boolean, String> check(int n) {
        if (n < 0) {
            return new Err<>("negative: " + n);
        }
        return new Ok<>(true);
    }
    public static Result<Integer, String> sum(String a, String b) {
        Nulls.param(a, "a");
        Nulls.param(b, "b");
        final var $t1 = parse(a);
        if ($t1.isErr()) return Result.propagate($t1);
        final var x = Result.unwrap($t1);
        final var $t2 = parse(b);
        if ($t2.isErr()) return Result.propagate($t2);
        final var y = Result.unwrap($t2);
        final var $t3 = check(x + y);
        if ($t3.isErr()) return Result.propagate($t3);
        return new Ok<>(x + y);
    }
    public static void main(String[] args) {
        System.out.println(sum("1", "2"));
        System.out.println(sum("1", "q"));
        System.out.println(sum("1", "-5"));
    }
}
