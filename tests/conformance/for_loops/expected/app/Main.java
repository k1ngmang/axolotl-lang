package app;
import axl.lang.*;
import org.jspecify.annotations.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@NullMarked public final class Main {
    private Main() {}
    public static Result<List<String>, String> names() {
        return new Ok<>(List.of("a", "bb"));
    }
    public static int total(List<Integer> xs) {
        var sum = 0;
        for (final var x : xs) {
            sum += x;
        }
        return sum;
    }
    public static Result<Integer, String> lengths() {
        var count = 0;
        final var $t1 = names();
        if ($t1.isErr()) return Result.propagate($t1);
        for (final var name : Result.unwrap($t1)) {
            count += name.length();
        }
        return new Ok<>(count);
    }
    public static void main(String[] args) {
        System.out.println(total(List.of(1, 2, 3)));
        System.out.println(lengths());
        final var seen = new ArrayList<String>();
        for (final var entry : Map.of("k", 1).entrySet()) {
            seen.add(entry.getKey() + "=" + entry.getValue());
        }
        System.out.println(seen);
        for (final var word : List.of("x", "y")) {
            final java.util.function.Supplier<String> shout = () -> word + "!";
            System.out.println(shout.get());
        }
    }
}
