package app;
import axl.lang.*;
import org.jspecify.annotations.*;
import java.awt.Point;

@NullMarked public final class Main {
    private Main() {}
    public static final Point p = new Point(1, 2);
    public static Result<String, String> snapshot(StringBuilder sb) {
        Nulls.param(sb, "sb");
        return new Ok<>(sb.toString());
    }
    public static Result<Integer, String> n() {
        return new Ok<>(4);
    }
    public static Result<Point, String> pt() {
        return new Ok<>(p);
    }
    public static int pick(Result<Integer, String> a, int b) {
        Nulls.param(a, "a");
        return b;
    }
    public static Point target() {
        System.out.println("target");
        return p;
    }
    public static Result<Integer, String> value() {
        System.out.println("value");
        return new Ok<>(10);
    }
    public static Result<String, String> run() {
        final var sb = new StringBuilder();
        final var $t1 = sb.append("a");
        final var $t2 = snapshot(sb);
        if ($t2.isErr()) return Result.propagate($t2);
        final var r = $t1.append(Result.unwrap($t2));
        final var $t3 = n();
        if ($t3.isErr()) return Result.propagate($t3);
        final var a = Math.max(1, Result.unwrap($t3));
        final var $t4 = System.out;
        final var $t5 = n();
        if ($t5.isErr()) return Result.propagate($t5);
        $t4.println(Result.unwrap($t5));
        final var $t6 = n();
        if ($t6.isErr()) return Result.propagate($t6);
        final var l = java.util.List.of(Result.unwrap($t6));
        final var $t7 = n();
        if ($t7.isErr()) return Result.propagate($t7);
        final var b = pick(new Ok<>(1), Result.unwrap($t7));
        final var $t8 = target();
        final var $t9 = value();
        if ($t9.isErr()) return Result.propagate($t9);
        $t8.x = Result.unwrap($t9);
        final var $t10 = pt();
        if ($t10.isErr()) return Result.propagate($t10);
        (Result.unwrap($t10)).y = 5;
        return new Ok<>(r.toString() + " " + a + " " + l + " " + b + " " + p.x + " " + p.y);
    }
    public static void main(String[] args) {
        System.out.println(run());
    }
}
