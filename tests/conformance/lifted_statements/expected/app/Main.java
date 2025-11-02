package app;
import axl.lang.*;
import org.jspecify.annotations.*;
import java.util.List;

@NullMarked public final class Main {
    private Main() {}
    public static Result<String, String> check(String s) {
        System.out.println("check " + s);
        return new Ok<>(s);
    }
    public static Result<Boolean, String> act() {
        System.out.println("act");
        return new Ok<>(true);
    }
    public static Result<Integer, String> validate(List<String> xs, boolean go) {
        final java.util.List<String> $p1 = new java.util.ArrayList<>();
        for (final var it : xs) {
            final var $t1 = check(it);
            if ($t1.isErr()) return Result.propagate($t1);
            $p1.add(Result.<String>unwrap($t1));
        }
        boolean $t2 = go;
        if ($t2) {
            final var $t3 = act();
            if ($t3.isErr()) return Result.propagate($t3);
            $t2 = Result.unwrap($t3);
        }
        final var $t4 = act();
        if ($t4.isErr()) return Result.propagate($t4);
        Result.unwrap($t4);
        return new Ok<>(xs.size());
    }
    public static void main(String[] args) {
        System.out.println(validate(List.of("a"), true));
    }
}
