package util;
import axl.lang.*;
import org.jspecify.annotations.*;

@NullMarked public final class Parse {
    private Parse() {}
    public static Result<Integer, String> doubled(String s) {
        Nulls.param(s, "s");
        final var $t1 = parse(s);
        if ($t1.isErr()) return Result.propagate($t1);
        final var n = Result.unwrap($t1);
        return new Ok<>(n * 2);
    }
    private static Result<Integer, String> parse(String s) {
        return Result.attempt(() -> Integer.parseInt(s), $ex1 -> "not a number: " + s);
    }
}
