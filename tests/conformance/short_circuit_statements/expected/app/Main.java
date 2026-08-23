package app;
import axl.lang.*;
import org.jspecify.annotations.*;
import java.util.List;

@NullMarked public final class Main {
    private Main() {}
    public static boolean doIt(String s) {
        System.out.println("did " + s);
        return true;
    }
    public static Result<Integer, String> p(String s) {
        return Result.attempt(() -> Integer.parseInt(s), e -> "bad " + s);
    }
    public static Result<Integer, String> run(List<String> xs) {
        final java.util.List<Integer> $p1 = new java.util.ArrayList<>();
        for (final var it : xs) {
            final var $t1 = p(it);
            if ($t1.isErr()) return Result.propagate($t1);
            $p1.add(Result.<Integer>unwrap($t1));
        }
        Nulls.stream($p1).forEach(it -> System.out.println(it));
        return new Ok<>(0);
    }
    public static void main(String[] args) {
        final var x = 3;
        if (x > 0) {
            doIt("and");
        }
        if (!(x < 0)) {
            doIt("or");
        }
        if (x < 0) {
            doIt("never");
        }
        final @Nullable String name = null;
        if (name == null) {
            System.out.println("no name");
        }
        System.out.println(run(List.of("1", "2")));
    }
}
