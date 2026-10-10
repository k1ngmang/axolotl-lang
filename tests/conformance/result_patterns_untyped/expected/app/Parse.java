package app;
import axl.lang.*;
import org.jspecify.annotations.*;

@NullMarked public final class Parse {
    private Parse() {}
    public static Result<Integer, String> number(String s) {
        Nulls.param(s, "s");
        return Result.attempt(() -> Integer.parseInt(s), e -> "not a number: " + s);
    }
}
