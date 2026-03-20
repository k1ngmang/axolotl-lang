package app;
import axl.lang.*;
import org.jspecify.annotations.*;

@NullMarked public final class Main {
    private Main() {}
    public static Result<Boolean, String> ok(String s) {
        return new Ok<>(s.length() > 1);
    }
    public static Result<Boolean, String> more(String s) {
        return new Ok<>(s.length() < 4);
    }
    public static Result<Boolean, String> bad(String s) {
        return new Ok<>(s.isEmpty());
    }
    public static Result<Integer, String> both(@Nullable String s) {
        do {
            if (s != null) {
                final var $t1 = ok(s);
                if ($t1.isErr()) return Result.propagate($t1);
                if (Result.unwrap($t1)) {
                    return new Ok<>(s.length());
                }
            }
            return new Ok<>(-1);
        } while (false);
    }
    public static Result<String, String> grow(@Nullable String start) {
        var s = start;
        while (true) {
            if (!(s != null)) break;
            final var $t1 = more(s);
            if ($t1.isErr()) return Result.propagate($t1);
            if (!(Result.unwrap($t1))) break;
            s = s + "x";
        }
        return new Ok<>(Nulls.or(s, () -> "none"));
    }
    public static Result<Integer, String> guard(@Nullable String s) {
        if (s == null) {
            return new Ok<>(0);
        }
        final var $t1 = bad(s);
        if ($t1.isErr()) return Result.propagate($t1);
        if (Result.unwrap($t1)) {
            return new Ok<>(0);
        }
        return new Ok<>(s.length());
    }
    public static Result<String, String> either(@Nullable String s) {
        do {
            if (!(s == null)) {
                final var $t1 = bad(s);
                if ($t1.isErr()) return Result.propagate($t1);
                if (!(Result.unwrap($t1))) {
                    if (s.length() > 2) {
                        System.out.println("long " + s.length());
                    } else {
                        System.out.println("short " + s.length());
                    }
                    break;
                }
            }
            System.out.println("empty");
        } while (false);
        return new Ok<>("done");
    }
    public static void main(String[] args) {
        System.out.println(both("abc"));
        System.out.println(both("a"));
        System.out.println(both(null));
        System.out.println(grow("a"));
        System.out.println(grow(null));
        System.out.println(guard(null));
        System.out.println(guard(""));
        System.out.println(guard("ab"));
        System.out.println(either(null));
        System.out.println(either("abcd"));
        System.out.println(either("ab"));
    }
}
