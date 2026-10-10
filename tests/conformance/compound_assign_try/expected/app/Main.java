package app;
import axl.lang.*;
import org.jspecify.annotations.*;

@NullMarked public final class Main {
    private Main() {}
    public static Result<Integer, String> bump(Box b) {
        Nulls.param(b, "b");
        b.value = 100;
        return new Ok<>(1);
    }
    public static Result<Integer, String> run() {
        final var b = new Box();
        final var $t1 = b.value;
        final var $t2 = bump(b);
        if ($t2.isErr()) return Result.propagate($t2);
        b.value = $t1;
        b.value += Result.unwrap($t2);
        System.out.println(b.value);
        final var c = new Box();
        final var $t3 = c.value;
        final var $t4 = bump(c);
        if ($t4.isErr()) return Result.propagate($t4);
        c.value = $t3 + Result.unwrap($t4);
        System.out.println(c.value);
        return new Ok<>(0);
    }
    public static void main(String[] args) {
        System.out.println(run());
    }
}
