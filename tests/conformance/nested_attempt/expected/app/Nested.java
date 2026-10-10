package app;
import axl.lang.*;
import org.jspecify.annotations.*;

@NullMarked public final class Nested {
    private Nested() {}
    public static int parseOrZero(String s) {
        Nulls.param(s, "s");
        return Integer.parseInt(s);
    }
    public static Result<Integer, String> read(String a, String b) {
        Nulls.param(a, "a");
        Nulls.param(b, "b");
        return Result.attempt(() -> Integer.parseInt(a) + Result.attempt(() -> parseOrZero(b), $ex1 -> 0).hashCode(), e -> "outer: " + e.getMessage());
    }
    public static void main(String[] args) {
        System.out.println(read("1", "x"));
        System.out.println(read("y", "2"));
    }
}
