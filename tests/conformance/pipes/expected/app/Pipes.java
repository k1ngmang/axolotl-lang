package app;
import axl.lang.*;
import org.jspecify.annotations.*;
import java.util.List;

@NullMarked public final class Pipes {
    private Pipes() {}
    public static void main(String[] args) {
        final List<Integer> numbers = List.of(5, -1, 3, 8, 3, 0);
        System.out.println(numbers.stream().filter(it -> it > 0).map(Nulls.nonNull(it -> it * 2)).toList());
        System.out.println(numbers.stream().distinct().sorted(java.util.Comparator.naturalOrder()).limit(3).toList());
        System.out.println(numbers.stream().sorted((a, b) -> b.compareTo(a)).skip(4).toList());
        System.out.println(numbers.stream().filter(n -> n > 4).count());
        System.out.println(numbers.stream().anyMatch(it -> it < 0));
        System.out.println(Nulls.first(numbers.stream().filter(it -> it > 100).iterator()));
        numbers.stream().filter(it -> it == 3).forEach(it -> System.out.println("three"));
    }
}
