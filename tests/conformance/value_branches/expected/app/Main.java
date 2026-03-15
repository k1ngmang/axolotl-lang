package app;
import axl.lang.*;
import org.jspecify.annotations.*;

@NullMarked public final class Main {
    private Main() {}
    public static @Nullable Integer maybeInt(boolean flag) {
        return (flag ? null : 5);
    }
    public static Result<Integer, String> load(int n) {
        return (n > 0 ? new Ok<>(n) : new Err<>("negative"));
    }
    public static Result<Object, String> anything(int n) {
        return (n > 1 ? new Ok<>("text") : new Ok<>(n));
    }
    public static @Nullable Integer pick(boolean flag) {
        return (flag ? maybeInt(flag) : Integer.valueOf(0));
    }
    public static @Nullable Integer byNumber(int n) {
        final var value = switch (n) {
            case 1 -> maybeInt(true);
            default -> Integer.valueOf(0);
        };
        return value;
    }
    public static int size(boolean flag, @Nullable String s) {
        return (flag ? Nulls.or(Nulls.map(s, $n1 -> $n1.length()), () -> 0) : Integer.valueOf(-1));
    }
    public static Result<String, String> checked(int n) {
        final var $t1 = load(n);
        if ($t1.isErr()) return Result.propagate($t1);
        final var word = (Result.unwrap($t1) > 1 ? "big" : "small");
        return new Ok<>(word);
    }
    public static Result<Boolean, String> isText(int n) {
        final var $t1 = anything(n);
        if ($t1.isErr()) return Result.propagate($t1);
        final var text = Result.unwrap($t1) instanceof String;
        return new Ok<>(text);
    }
    public static void main(String[] args) {
        System.out.println(pick(true));
        System.out.println(pick(false));
        System.out.println(byNumber(1));
        System.out.println(byNumber(2));
        System.out.println(size(true, null));
        System.out.println(size(true, "abc"));
        System.out.println(size(false, "abc"));
        System.out.println(checked(2));
        System.out.println(checked(-1));
        System.out.println(isText(1));
        System.out.println(isText(2));
    }
}
