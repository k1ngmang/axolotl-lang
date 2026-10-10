package app;
import axl.lang.*;
import org.jspecify.annotations.*;

@NullMarked public final class Main {
    private Main() {}
    public static Result<Integer, String> parse(String s) {
        Nulls.param(s, "s");
        return Result.attempt(() -> Integer.parseInt(s), e -> "bad " + s);
    }
    public static String run(boolean flag, String s) {
        Nulls.param(s, "s");
        if (flag) {
            final var r = parse(s);
            if (r instanceof Err(var r$1)) {
                return "error " + r$1;
            }
            return "value " + Result.unwrap(r);
        } else if (s.isEmpty()) {
            final var r = parse("0");
            switch (r) {
                case Ok(var r$2) -> {
                    return "empty " + r$2;
                }
                case Err(var r$3) -> {
                    return r$3;
                }
            }
        }
        return "off";
    }
    public static void main(String[] args) {
        System.out.println(run(true, "x"));
        System.out.println(run(true, "7"));
        System.out.println(run(false, ""));
        System.out.println(run(false, "q"));
    }
}
