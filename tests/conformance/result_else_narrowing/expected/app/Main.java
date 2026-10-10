package app;
import axl.lang.*;
import org.jspecify.annotations.*;

@NullMarked public final class Main {
    private Main() {}
    public static Result<Integer, String> parse(String s) {
        Nulls.param(s, "s");
        return Result.attempt(() -> Integer.parseInt(s), e -> "bad " + s);
    }
    public static String show(String s) {
        Nulls.param(s, "s");
        final var r = parse(s);
        if (r instanceof Err(var r$1)) {
            return "error " + r$1;
        } else {
            return "value " + (Result.unwrap(r) + 1);
        }
    }
    public static String label(String s) {
        Nulls.param(s, "s");
        final var r = parse(s);
        if (r instanceof Ok(var r$1)) {
            return "ok " + r$1;
        } else {
            return Result.unwrapError(r).toUpperCase();
        }
    }
    public static void main(String[] args) {
        System.out.println(show("1"));
        System.out.println(show("x"));
        System.out.println(label("2"));
        System.out.println(label("y"));
    }
}
