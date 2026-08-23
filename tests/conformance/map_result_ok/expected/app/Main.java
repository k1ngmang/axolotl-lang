package app;
import axl.lang.*;
import org.jspecify.annotations.*;
import java.util.List;

@NullMarked public final class Main {
    private Main() {}
    public static Result<Integer, String> p(String s) {
        return Result.attempt(() -> Integer.parseInt(s), $ex1 -> "bad " + s);
    }
    public static Result<List<List<Integer>>, String> f(List<List<String>> xss) {
        final java.util.List<List<Integer>> $p1 = new java.util.ArrayList<>();
        for (final var row : xss) {
            final var $t1 = new Ok<>(Nulls.stream(row).map(Nulls.nonNull(it -> it.length())).toList());
            $p1.add(Result.<List<Integer>>unwrap($t1));
        }
        final List<List<Integer>> r = $p1;
        return new Ok<>(r);
    }
    public static void main(String[] args) {
        System.out.println(f(List.of(List.of("a", "bb"))));
    }
}
