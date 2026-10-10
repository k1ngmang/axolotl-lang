package app;
import axl.lang.*;
import org.jspecify.annotations.*;

@NullMarked public final class Main {
    private Main() {}
    public static Result<Integer, String> q(int n) {
        return (n > 1000 ? new Err<>("big") : new Ok<>(n));
    }
    public static Result<String, String> word(String s) {
        Nulls.param(s, "s");
        return new Ok<>(s);
    }
    public static Result<Integer, String> doubled(@Nullable Integer n) {
        final var $t1 = n;
        final var $t2 = java.util.Objects.isNull($t1) ? q(7) : null;
        if ($t2 != null && $t2.isErr()) return Result.propagate($t2);
        final var x = (java.util.Objects.nonNull($t1) ? $t1 : Result.unwrap(java.util.Objects.requireNonNull($t2))) * 2;
        return new Ok<>(x);
    }
    public static Result<String, String> shout(@Nullable String s) {
        final var $t1 = s;
        final var $t2 = java.util.Objects.isNull($t1) ? word("fallback") : null;
        if ($t2 != null && $t2.isErr()) return Result.propagate($t2);
        final var x = (java.util.Objects.nonNull($t1) ? $t1 : Result.unwrap(java.util.Objects.requireNonNull($t2))) + "!";
        return new Ok<>(x);
    }
    public static Result<Boolean, String> added(@Nullable Integer n) {
        final var $t1 = n;
        final var $t2 = java.util.Objects.isNull($t1) ? q(7) : null;
        if ($t2 != null && $t2.isErr()) return Result.propagate($t2);
        final var x = 0 + (java.util.Objects.nonNull($t1) ? $t1 : Result.unwrap(java.util.Objects.requireNonNull($t2))) > 5;
        return new Ok<>(x);
    }
    public static void main(String[] args) {
        System.out.println(doubled(null));
        System.out.println(doubled(3));
        System.out.println(shout(null));
        System.out.println(shout("val"));
        System.out.println(added(3));
        System.out.println(added(null));
    }
}
