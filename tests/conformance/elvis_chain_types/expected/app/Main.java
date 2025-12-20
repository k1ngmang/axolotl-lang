package app;
import axl.lang.*;
import org.jspecify.annotations.*;

@NullMarked public final class Main {
    private Main() {}
    public static Result<StringBuilder, String> sb() {
        return new Ok<>(new StringBuilder("sb"));
    }
    public static Result<Integer, String> none() {
        return new Ok<>(-1);
    }
    public static Result<String, String> run(@Nullable String s) {
        @Nullable CharSequence $t1 = s;
        if (java.util.Objects.isNull($t1)) {
            final var $t2 = sb();
            if ($t2.isErr()) return Result.propagate($t2);
            $t1 = Result.unwrap($t2);
        }
        final CharSequence cs = $t1;
        return new Ok<>(cs.toString());
    }
    public static Result<Integer, String> length(@Nullable String s) {
        @Nullable Integer $t1 = Nulls.map(s, $n1 -> $n1.length());
        if (java.util.Objects.isNull($t1)) {
            final var $t2 = none();
            if ($t2.isErr()) return Result.propagate($t2);
            $t1 = Result.unwrap($t2);
        }
        final int n = $t1;
        return new Ok<>(n);
    }
    public static void main(String[] args) {
        System.out.println(run(null));
        System.out.println(run("x"));
        System.out.println(length(null));
    }
}
