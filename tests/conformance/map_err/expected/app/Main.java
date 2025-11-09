package app;
import axl.lang.*;
import org.jspecify.annotations.*;

@NullMarked public final class Main {
    private Main() {}
    public static Result<Integer, Integer> parse(String s) {
        return Result.attempt(() -> Integer.parseInt(s), $ex1 -> 1);
    }
    public static Result<Integer, String> describe(String s) {
        final var $t1 = parse(s).mapErr(code -> "error " + code);
        if ($t1.isErr()) return Result.propagate($t1);
        final var n = Result.unwrap($t1);
        return new Ok<>(n * 2);
    }
    public static void main(String[] args) {
        System.out.println(describe("21"));
        System.out.println(describe("x"));
    }
}
