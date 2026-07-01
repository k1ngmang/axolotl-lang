package app;
import axl.lang.*;
import org.jspecify.annotations.*;
import java.util.List;

@NullMarked public final class Main {
    private Main() {}
    public static Result<Integer, String> q() {
        return new Ok<>(0);
    }
    public static Result<Integer, String> chain(boolean a, boolean b) {
        final Result<Integer, String> r = (a ? q() : (b ? new Ok<>(1) : new Err<>("x")));
        final Result<Integer, String> s = (a ? ((b ? new Ok<>(1) : new Err<>("x"))) : q());
        return (r instanceof Ok(var r$1) ? s : r);
    }
    public static Result<List<Integer>, String> positives(List<Integer> xs) {
        final java.util.List<Integer> $p1 = new java.util.ArrayList<>();
        for (final var it : xs) {
            final Result<Integer, String> $t1 = (it > 0 ? new Ok<>(it) : new Err<>("neg"));
            if ($t1.isErr()) return Result.propagate($t1);
            $p1.add(Result.<Integer>unwrap($t1));
        }
        final List<Integer> ys = $p1;
        return new Ok<>(ys);
    }
    public static void main(String[] args) {
        System.out.println(chain(false, true));
        System.out.println(positives(List.of(1, 2)));
        System.out.println(positives(List.of(1, -2)));
        final List<Result<Integer, String>> rs = List.of(1, -1).stream().<Result<Integer, String>>map(Nulls.nonNull(it -> (it > 0 ? new Ok<>(it) : new Err<>("neg")))).toList();
        System.out.println(rs);
    }
}
