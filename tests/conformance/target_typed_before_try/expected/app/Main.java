package app;
import axl.lang.*;
import org.jspecify.annotations.*;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

@NullMarked public final class Main {
    private Main() {}
    public static Result<Integer, String> g() {
        return new Ok<>(1);
    }
    public static int take(Optional<String> o, int n) {
        return n;
    }
    public static int sortBy(Comparator<String> c, int n) {
        return n;
    }
    public static int wrapped(Result<List<String>, String> r, int n) {
        return n;
    }
    public static int copied(List<String> xs, int n) {
        return n;
    }
    public static Result<Integer, String> sum() {
        final var $t1 = g();
        if ($t1.isErr()) return Result.propagate($t1);
        final var a = take(Optional.empty(), Result.unwrap($t1));
        final var $t2 = g();
        if ($t2.isErr()) return Result.propagate($t2);
        final var b = sortBy(Comparator.naturalOrder(), Result.unwrap($t2));
        final var $t3 = g();
        if ($t3.isErr()) return Result.propagate($t3);
        final var c = wrapped(new Ok<>(List.of()), Result.unwrap($t3));
        final var $t4 = g();
        if ($t4.isErr()) return Result.propagate($t4);
        final var d = copied(new ArrayList<>(List.of()), Result.unwrap($t4));
        return new Ok<>(a + b + c + d);
    }
    public static void main(String[] args) {
        System.out.println(sum());
    }
}
