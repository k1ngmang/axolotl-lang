package app;
import axl.lang.*;
import org.jspecify.annotations.*;

@NullMarked public final class Logic {
    private Logic() {}
    public static Result<Boolean, String> flag(String label, boolean ok) {
        System.out.println("try " + label);
        if (ok) {
            return new Ok<>(true);
        }
        return new Err<>("failed " + label);
    }
    public static Result<Integer, String> number(String label, boolean ok) {
        System.out.println("try " + label);
        if (ok) {
            return new Ok<>(7);
        }
        return new Err<>("failed " + label);
    }
    public static Result<Boolean, String> both(boolean first, boolean ok) {
        boolean $t1 = false;
        if (first) {
            final var $t2 = flag("and", ok);
            if ($t2.isErr()) return Result.propagate($t2);
            $t1 = Result.unwrap($t2);
        }
        final var result = $t1;
        return new Ok<>(result);
    }
    public static Result<Boolean, String> either(boolean first, boolean ok) {
        boolean $t1 = true;
        if (!first) {
            final var $t2 = flag("or", ok);
            if ($t2.isErr()) return Result.propagate($t2);
            $t1 = Result.unwrap($t2);
        }
        return new Ok<>($t1);
    }
    public static Result<Integer, String> fallback(@Nullable Integer value, boolean ok) {
        final var $t1 = value;
        final var $t2 = java.util.Objects.isNull($t1) ? number("elvis", ok) : null;
        if ($t2 != null && $t2.isErr()) return Result.propagate($t2);
        final var result = java.util.Objects.nonNull($t1) ? $t1 : Result.unwrap(java.util.Objects.requireNonNull($t2));
        return new Ok<>(result + 1);
    }
    public static void main(String[] args) {
        System.out.println(both(false, false));
        System.out.println(both(true, true));
        System.out.println(both(true, false));
        System.out.println(either(true, false));
        System.out.println(either(false, true));
        System.out.println(fallback(1, false));
        System.out.println(fallback(null, true));
        System.out.println(fallback(null, false));
    }
}
