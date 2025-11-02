package app;
import axl.lang.*;
import org.jspecify.annotations.*;
import java.util.List;

@NullMarked public final class Main {
    private Main() {}
    public static String it() {
        return "top";
    }
    public static void main(String[] args) {
        final var xs = List.of(1, 2);
        System.out.println(xs.stream().map(Nulls.nonNull(it -> it * 2)).toList());
        final var xss = List.of(List.of(1, 2), List.of(3));
        System.out.println(xss.stream().map(Nulls.nonNull(row -> row.stream().map(Nulls.nonNull(it -> it + 1)).toList())).toList());
        System.out.println(it());
    }
}
