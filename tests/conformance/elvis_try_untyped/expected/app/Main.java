package app;
import axl.lang.*;
import org.jspecify.annotations.*;
import java.util.ArrayList;
import java.util.List;

@NullMarked public final class Main {
    private Main() {}
    public static Result<List<String>, String> load() {
        return new Ok<>(List.of("a"));
    }
    public static Result<List<String>, String> fail() {
        return new Err<>("no list");
    }
    public static Result<Integer, String> loaded(@Nullable ArrayList<String> xs) {
        final var $t1 = xs;
        final var $t2 = java.util.Objects.isNull($t1) ? load() : null;
        if ($t2 != null && $t2.isErr()) return Result.propagate($t2);
        final var zs = (java.util.Objects.nonNull($t1) ? $t1 : Result.unwrap(java.util.Objects.requireNonNull($t2)));
        System.out.println(zs);
        return new Ok<>(zs.size());
    }
    public static Result<Integer, String> failed(@Nullable ArrayList<String> xs) {
        final var $t1 = xs;
        final var $t2 = java.util.Objects.isNull($t1) ? fail() : null;
        if ($t2 != null && $t2.isErr()) return Result.propagate($t2);
        final var zs = (java.util.Objects.nonNull($t1) ? $t1 : Result.unwrap(java.util.Objects.requireNonNull($t2)));
        return new Ok<>(zs.size());
    }
    public static void main(String[] args) {
        System.out.println(loaded(null));
        System.out.println(failed(null));
        System.out.println(failed(new ArrayList<String>()));
    }
}
