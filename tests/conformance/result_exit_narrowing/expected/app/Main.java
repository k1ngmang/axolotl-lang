package app;
import axl.lang.*;
import org.jspecify.annotations.*;

@NullMarked public final class Main {
    private Main() {}
    public static Result<Integer, String> parse(String s) {
        Nulls.param(s, "s");
        return Result.attempt(() -> Integer.parseInt(s), e -> "not a number: " + s);
    }
    public static String twice(String s) {
        Nulls.param(s, "s");
        final var r = parse(s);
        if (r instanceof Err(var r$1)) {
            return "error " + r$1;
        }
        return "double " + (Result.unwrap(r) * 2);
    }
    public static String describe(String s) {
        Nulls.param(s, "s");
        final var r = parse(s);
        if (r instanceof Ok(var r$1)) {
            return "fine";
        }
        return Result.unwrapError(r).toUpperCase();
    }
    public static void show(String s) {
        Nulls.param(s, "s");
        final var r = parse(s);
        if (r instanceof Err(var r$1)) {
            System.out.println("skip " + r$1);
            return;
        }
        System.out.println(Result.unwrap(r) + 1);
    }
    public static void main(String[] args) {
        System.out.println(twice("21"));
        System.out.println(twice("x"));
        System.out.println(describe("5"));
        System.out.println(describe("y"));
        show("9");
        show("z");
    }
}
