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
        var $t1 = n;
        if (java.util.Objects.isNull($t1)) {
            final var $t2 = ok(1);
            if ($t2.isErr()) return Result.propagate($t2);
            $t1 = Result.unwrap($t2);
        }
        final var b = $t1;
        return new Ok<>(a + b);
    }
    public static void main(String[] args) {
        System.out.println(run(3));
    }
}
