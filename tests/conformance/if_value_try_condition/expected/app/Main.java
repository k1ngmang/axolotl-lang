package app;
import axl.lang.*;
import org.jspecify.annotations.*;

@NullMarked public final class Main {
    private Main() {}
    public static Result<Boolean, String> check(String s) {
        return new Ok<>(s.length() > 1);
    }
    public static Result<Integer, String> size(@Nullable String s) {
        boolean $t1 = false;
        if (s != null) {
            final var $t2 = check(s);
            if ($t2.isErr()) return Result.propagate($t2);
            $t1 = Result.unwrap($t2);
        }
        final var n = ($t1 && s != null ? s.length() : Integer.valueOf(0));
        return new Ok<>(n);
    }
    public static void main(String[] args) {
        System.out.println(size("abc"));
        System.out.println(size(null));
        System.out.println(size("a"));
    }
}
