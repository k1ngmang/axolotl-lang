package app;
import axl.lang.*;
import org.jspecify.annotations.*;
import java.util.List;
import java.util.ArrayList;

@NullMarked public final class Lambdas {
    private Lambdas() {}
    public static void main(String[] args) {
        final List<String> words = List.of("b", "a", "c");
        words.forEach(it -> System.out.println(it));
        final java.util.function.Function<String, String> upper = s -> s.toUpperCase();
        System.out.println(upper.apply("x"));
        final List<String> sorted = new ArrayList(words);
        sorted.sort((a, b) -> a.compareTo(b));
        System.out.println(sorted);
        final java.util.function.Function<String, Integer> counted = s -> {
            final var n = s.length();
            return n * 10;
        };
        System.out.println(counted.apply("abc"));
        final List<String> names = new ArrayList<String>();
        names.add("z");
        System.out.println(names);
        final Runnable run = () -> System.out.println("ran");
        run.run();
    }
}
