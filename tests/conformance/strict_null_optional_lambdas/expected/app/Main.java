package app;
import axl.lang.*;
import org.jspecify.annotations.*;
import java.util.List;
import java.util.Optional;

@NullMarked public final class Main {
    private Main() {}
    public static void show(Optional<String> o) {
        Nulls.param(o, "o");
        o.ifPresent(it -> System.out.println(List.of(it)));
        o.ifPresentOrElse(it -> System.out.println(List.of(it).size()), () -> System.out.println("empty"));
    }
    public static int size(Optional<String> o) {
        Nulls.param(o, "o");
        return o.map(it -> List.of(it).size()).orElse(0);
    }
    public static String longest(Optional<String> o) {
        Nulls.param(o, "o");
        return o.filter(it -> List.of(it).size() == 1).flatMap(it -> Optional.of(List.of(it).getFirst())).or(() -> Optional.of("none")).get();
    }
    public static void main(String[] args) {
        show(Optional.of("a"));
        show(Optional.empty());
        System.out.println(size(Optional.of("b")));
        System.out.println(longest(Optional.of("c")) + " " + longest(Optional.empty()));
    }
}
