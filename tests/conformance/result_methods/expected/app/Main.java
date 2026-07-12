package app;
import axl.lang.*;
import org.jspecify.annotations.*;

@NullMarked public final class Main {
    private Main() {}
    public static Result<Integer, String> parse(String s) {
        return Result.attempt(() -> Integer.parseInt(s), e -> "bad " + s);
    }
    public static void main(String[] args) {
        final var good = parse("20");
        final var bad = parse("x");
        System.out.println(good.isOk());
        System.out.println(bad.isOk());
        System.out.println(bad.isErr());
        System.out.println(good.map(n -> n + 1).getOrElse(0));
        System.out.println(bad.map(n -> n + 1).getOrElse(-1));
        System.out.println(good.map(n -> "n=" + n).getOrElse("none"));
        System.out.println(bad.mapErr(m -> m.length()).isErr());
    }
}
