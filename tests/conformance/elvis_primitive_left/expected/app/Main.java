package app;
import axl.lang.*;
import org.jspecify.annotations.*;

@NullMarked public final class Main {
    private Main() {}
    public static Result<Integer, String> ok(int v) {
        return new Ok<>(v);
    }
    public static Result<Integer, String> run(int n) {
        final var a = Nulls.or(n, 5);
        final var $t1 = n;
        final var $t2 = java.util.Objects.isNull($t1) ? ok(1) : null;
        if ($t2 != null && $t2.isErr()) return Result.propagate($t2);
        final var b = java.util.Objects.nonNull($t1) ? $t1 : Result.unwrap(java.util.Objects.requireNonNull($t2));
        return new Ok<>(a + b);
    }
    public static void main(String[] args) {
        System.out.println(run(3));
    }
}
