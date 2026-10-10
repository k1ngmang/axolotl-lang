package app;
import axl.lang.*;
import org.jspecify.annotations.*;
import java.util.List;

@NullMarked public final class Main {
    private Main() {}
    public static Result<Integer, String> checked(int v) {
        return new Ok<>(v);
    }
    public static @Nullable Integer none() {
        return null;
    }
    public static Result<Integer, String> pick(List<Integer> xs) {
        Nulls.param(xs, "xs");
        var $t1 = (Nulls.first(xs.stream().iterator()));
        if (java.util.Objects.isNull($t1)) {
            final var $t2 = none();
            final var $t3 = java.util.Objects.isNull($t2) ? checked(6) : null;
            if ($t3 != null && $t3.isErr()) return Result.propagate($t3);
            final var $t4 = (java.util.Objects.nonNull($t2) ? $t2 : Result.unwrap(java.util.Objects.requireNonNull($t3)));
            $t1 = $t4;
        }
        final var x = $t1;
        return new Ok<>(x);
    }
    public static void main(String[] args) {
        System.out.println(pick(List.of()));
        System.out.println(pick(List.of(2)));
    }
}
