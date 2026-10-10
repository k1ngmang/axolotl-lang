package calc;
import axl.lang.*;
import org.jspecify.annotations.*;

@NullMarked public final class Calc {
    private Calc() {}
    public static Result<Integer, String> parse(String s) {
        Nulls.param(s, "s");
        return Result.attempt(() -> Integer.parseInt(s), $ex1 -> "bad input: " + s);
    }
    public static Result<Integer, String> inc(String s) {
        Nulls.param(s, "s");
        final var $t1 = parse(s);
        if ($t1.isErr()) return Result.propagate($t1);
        final var n = Result.unwrap($t1);
        return new Ok<>(n + 1);
    }
    public static void main(String[] args) {
        System.out.println(inc("41"));
        System.out.println(inc("x"));
    }
}
