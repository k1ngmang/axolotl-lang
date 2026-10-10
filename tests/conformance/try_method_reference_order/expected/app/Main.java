package app;
import axl.lang.*;
import org.jspecify.annotations.*;
import java.util.function.Supplier;

@NullMarked public final class Main {
    private Main() {}
    public static Result<Integer, String> ok(String label) {
        Nulls.param(label, "label");
        System.out.println("try " + label);
        return new Ok<>(1);
    }
    public static StringBuilder make() {
        System.out.println("make");
        return new StringBuilder("sb");
    }
    public static String use(Supplier<String> f, int n) {
        Nulls.param(f, "f");
        return f.get() + n;
    }
    public static String apply(java.util.function.Function<Object, String> f, int n) {
        Nulls.param(f, "f");
        return f.apply(n);
    }
    public static Result<String, String> run() {
        final var $t1 = make();
        final var $t2 = ok("a");
        if ($t2.isErr()) return Result.propagate($t2);
        final var a = use($t1::toString, Result.unwrap($t2));
        final var $t3 = ok("b");
        if ($t3.isErr()) return Result.propagate($t3);
        final var b = apply(String::valueOf, Result.unwrap($t3));
        final var $t4 = ok("c");
        if ($t4.isErr()) return Result.propagate($t4);
        final var c = apply(java.util.Objects::toString, Result.unwrap($t4));
        return new Ok<>(a + b + c);
    }
    public static void main(String[] args) {
        System.out.println(run());
    }
}
