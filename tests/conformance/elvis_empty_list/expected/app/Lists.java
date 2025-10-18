package app;
import axl.lang.*;
import org.jspecify.annotations.*;
import java.util.List;
import static java.util.Collections.emptyList;

@NullMarked public final class Lists {
    private Lists() {}
    public static List<String> pick(List<String> other) {
        return Nulls.or(emptyList(), () -> other);
    }
    public static void main(String[] args) {
        System.out.println(pick(List.of("a")));
    }
}
