package util;
import axl.lang.*;
import org.jspecify.annotations.*;

@NullMarked public final class Parse {
    private Parse() {}
    public static Result<Integer, String> parse(String s) {
        return Result.attempt(() -> Integer.parseInt(s), e -> "not a number: " + s + " (" + e.getMessage() + ")");
    }
    private static int twice(int n) {
        return n * 2;
    }
    public static Result<Integer, String> doubled(String s) {
        final var $t1 = parse(s);
        if ($t1.isErr()) return Result.propagate($t1);
        final var n = Result.unwrap($t1);
        return new Ok<>(twice(n));
    }
}
