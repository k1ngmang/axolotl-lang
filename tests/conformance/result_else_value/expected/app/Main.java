package app;
import axl.lang.*;
import org.jspecify.annotations.*;

@NullMarked public final class Main {
    private Main() {}
    public static Result<Integer, String> parse(String s) {
        Nulls.param(s, "s");
        return (s.isEmpty() ? new Err<>("empty") : new Ok<>(s.length()));
    }
    public static String describe(Result<Integer, String> r) {
        Nulls.param(r, "r");
        return (r instanceof Err(var r$1) ? "error " + r$1 : "value " + Result.unwrap(r));
    }
    public static String flipped(Result<Integer, String> r) {
        Nulls.param(r, "r");
        return (r instanceof Ok(var r$1) ? "value " + r$1 : "error " + Result.unwrapError(r));
    }
    public static void main(String[] args) {
        System.out.println(describe(parse("")));
        System.out.println(describe(parse("abc")));
        System.out.println(flipped(parse("")));
        System.out.println(flipped(parse("ab")));
    }
}
