package app;
import axl.lang.*;
import org.jspecify.annotations.*;

@NullMarked public final class Order {
    private Order() {}
    public static int log(String label, int value) {
        System.out.println("eval " + label);
        return value;
    }
    public static Result<Integer, String> check(String label, boolean ok) {
        System.out.println("try " + label);
        if (ok) {
            return new Ok<>(2);
        }
        return new Err<>("failed " + label);
    }
    public static int sum3(int a, int b, int c) {
        return a + b + c;
    }
    public static Result<Integer, String> run(boolean ok) {
        final var $t1 = log("a", 1);
        final var $t2 = check("b", ok);
        if ($t2.isErr()) return Result.propagate($t2);
        final var total = sum3($t1, Result.unwrap($t2), log("c", 3));
        System.out.println("total " + total);
        final var $t3 = check("e", true);
        if ($t3.isErr()) return Result.propagate($t3);
        final var $t4 = check("f", ok);
        if ($t4.isErr()) return Result.propagate($t4);
        System.out.println(log("d", Result.unwrap($t3) + Result.unwrap($t4)));
        final var $t5 = check("g", true);
        if ($t5.isErr()) return Result.propagate($t5);
        return new Ok<>(Result.unwrap($t5) * total);
    }
    public static void main(String[] args) {
        System.out.println(run(true));
        System.out.println(run(false));
    }
}
