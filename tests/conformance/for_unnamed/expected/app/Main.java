package app;
import axl.lang.*;
import org.jspecify.annotations.*;
import java.util.List;

@NullMarked public final class Main {
    private Main() {}
    public static int count(List<String> xs) {
        var n = 0;
        for (final var $unused1 : xs) {
            n += 1;
        }
        return n;
    }
    public static void main(String[] args) {
        System.out.println(count(List.of("a", "b")));
    }
}
