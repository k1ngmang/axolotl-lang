package app;
import axl.lang.*;
import org.jspecify.annotations.*;

@NullMarked public final class Main {
    private Main() {}
    public static int viaIf(String a) {
        Nulls.param(a, "a");
        final var x = Result.attempt(() -> Integer.parseInt(a), $ex1 -> -1);
        if (x instanceof Ok(var x$1)) {
            return x$1 + 1;
        }
        return 0;
    }
    public static String viaWhen(String a) {
        Nulls.param(a, "a");
        final var x = Result.attempt(() -> Integer.parseInt(a), $ex1 -> -1);
        switch (x) {
            case Ok(var x$1) -> {
                return "ok " + x$1;
            }
            case Err(var x$2) -> {
                return "err " + x$2;
            }
        }
    }
    public static int both(String a, String b) {
        Nulls.param(a, "a");
        Nulls.param(b, "b");
        final var x = Result.attempt(() -> Integer.parseInt(a), $ex1 -> -1);
        final var y = Result.attempt(() -> Integer.parseInt(b), $ex2 -> -1);
        if (x instanceof Ok(var x$1) && y instanceof Ok(var y$2)) {
            return x$1 + y$2;
        }
        return -1;
    }
    public static String otherFile(String s) {
        Nulls.param(s, "s");
        final var r = Parse.number(s);
        if (!(r instanceof Ok(var r$1))) {
            return "bad";
        }
        return "" + (r$1 * 2);
    }
    public static String whole(String s) {
        Nulls.param(s, "s");
        final var r = Parse.number(s);
        switch (r) {
            case Ok(var r$1) -> {
                return describe(r) + " " + r$1.toString().length();
            }
            case Err(var r$2) -> {
                return describe(r);
            }
        }
    }
    public static String describe(Result<Integer, String> r) {
        Nulls.param(r, "r");
        return (r instanceof Ok(var r$1) ? "value" : "error");
    }
    public static void main(String[] args) {
        System.out.println(viaIf("3"));
        System.out.println(viaWhen("4"));
        System.out.println(viaWhen("x"));
        System.out.println(both("1", "2"));
        System.out.println(both("1", "y"));
        System.out.println(otherFile("21"));
        System.out.println(otherFile("z"));
        System.out.println(whole("123"));
        System.out.println(whole("q"));
    }
}
