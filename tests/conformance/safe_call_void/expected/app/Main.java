package app;
import axl.lang.*;
import org.jspecify.annotations.*;

@NullMarked public final class Main {
    private Main() {}
    public static @Nullable StringBuilder find(String s) {
        Nulls.param(s, "s");
        return new StringBuilder(s);
    }
    public static Result<String, String> key() {
        return new Ok<>("key");
    }
    public static Result<Integer, String> run() {
        final var $t1 = key();
        if ($t1.isErr()) return Result.propagate($t1);
        final var $n1 = find(Result.unwrap($t1));
        if ($n1 != null) {
            $n1.setLength(1);
        }
        final var $t2 = key();
        if ($t2.isErr()) return Result.propagate($t2);
        final var $n2 = find(Result.unwrap($t2));
        if ($n2 != null) {
            final var $n3 = Nulls.typed($n2.append("!")).value();
            if ($n3 != null) {
                $n3.setLength(2);
            }
        }
        return new Ok<>(1);
    }
    public static void main(String[] args) {
        System.out.println(run());
    }
}
