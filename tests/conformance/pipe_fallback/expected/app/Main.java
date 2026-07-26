package app;
import axl.lang.*;
import org.jspecify.annotations.*;
import java.util.List;

@NullMarked public final class Main {
    private Main() {}
    public static String top(List<String> names) {
        return Nulls.or(Nulls.first(names.stream().sorted(java.util.Comparator.naturalOrder()).iterator()), () -> "none");
    }
    public static void main(String[] args) {
        System.out.println(top(List.of("pear", "apple")));
        System.out.println(top(List.of()));
        final var numbers = List.of(3, 8, 5);
        final var firstBig = Nulls.or(Nulls.first(numbers.stream().filter(it -> it > 4).iterator()), 0);
        System.out.println(firstBig + 1);
        System.out.println("top " + Nulls.or(Nulls.first(numbers.stream().filter(it -> it > 10).iterator()), -1));
    }
}
