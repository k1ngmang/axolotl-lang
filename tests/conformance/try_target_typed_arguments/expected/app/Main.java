package app;
import axl.lang.*;
import org.jspecify.annotations.*;
import java.util.ArrayList;
import java.util.List;

@NullMarked public final class Main {
    private Main() {}
    public static Result<Integer, String> ok(int v) {
        return new Ok<>(v);
    }
    public static int two() {
        return 2;
    }
    public static String take(Result<Integer, String> r, int n) {
        return r.toString() + n;
    }
    public static int size(List<String> xs, int n) {
        return xs.size() + n;
    }
    public static Result<String, String> run() {
        final var $t1 = two();
        final var $t2 = ok(3);
        if ($t2.isErr()) return Result.propagate($t2);
        final var x = take(new Ok<>($t1), Result.unwrap($t2));
        final var $t3 = "e" + two();
        final var $t4 = ok(4);
        if ($t4.isErr()) return Result.propagate($t4);
        final var y = take(new Err<>($t3), Result.unwrap($t4));
        final var $t5 = ok(1);
        if ($t5.isErr()) return Result.propagate($t5);
        final var a = size(List.of(), Result.unwrap($t5));
        final var $t6 = ok(2);
        if ($t6.isErr()) return Result.propagate($t6);
        final var b = size(new ArrayList<>(), Result.unwrap($t6));
        return new Ok<>(x + y + a + b);
    }
    public static void main(String[] args) {
        System.out.println(run());
    }
}
