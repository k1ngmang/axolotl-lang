package app;
import axl.lang.*;
import org.jspecify.annotations.*;

@NullMarked public final class Main {
    private Main() {}
    public static Result<Integer, String> ok(int v) {
        return new Ok<>(v);
    }
    public static Result<Boolean, String> run(@Nullable String s) {
        if (s != null) {
            final var $t1 = s.length();
            final var $t2 = ok(1);
            if ($t2.isErr()) return Result.propagate($t2);
            if ($t1 > Result.unwrap($t2)) {
                System.out.println("long " + s.length());
            }
        }
        final @Nullable String t = s;
        boolean $t3 = true;
        if (!(t == null)) {
            final var $t4 = t.length();
            final var $t5 = ok(5);
            if ($t5.isErr()) return Result.propagate($t5);
            $t3 = $t4 < Result.unwrap($t5);
        }
        final var shortish = $t3;
        return new Ok<>(shortish);
    }
    public static void main(String[] args) {
        System.out.println(run("abc"));
        System.out.println(run(null));
    }
}
