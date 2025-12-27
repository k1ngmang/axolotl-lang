package app;
import axl.lang.*;
import org.jspecify.annotations.*;

@NullMarked public final class Main {
    private Main() {}
    public static Result<StringBuilder, String> sbRes() {
        return new Ok<>(new StringBuilder("abc"));
    }
    public static Result<Integer, String> run() {
        final var $t1 = sbRes();
        if ($t1.isErr()) return Result.propagate($t1);
        (Result.unwrap($t1)).setLength(1);
        return new Ok<>(0);
    }
    public static void main(String[] args) {
        System.out.println(run());
    }
}
