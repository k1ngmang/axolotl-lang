package app;
import axl.lang.*;
import org.jspecify.annotations.*;

@NullMarked public final class Main {
    private Main() {}
    public static @Nullable Integer n(@Nullable Integer v) {
        return v;
    }
    public static Result<@Nullable Integer, String> tn(@Nullable Integer v) {
        return new Ok<>(v);
    }
    public static Result<@Nullable String, String> ts(@Nullable String v) {
        return new Ok<>(v);
    }
    public static int f() {
        return 9;
    }
    public static Result<Integer, String> tried() {
        final var $t1 = n(null);
        final var $t2 = java.util.Objects.isNull($t1) ? tn(4) : null;
        if ($t2 != null && $t2.isErr()) return Result.propagate($t2);
        return new Ok<>(Nulls.<Integer>or((java.util.Objects.nonNull($t1) ? $t1 : Result.<@Nullable Integer>unwrap(java.util.Objects.requireNonNull($t2))), () -> f()));
    }
    public static @Nullable String sn(@Nullable String v) {
        return v;
    }
    public static Result<String, String> text() {
        final var $t1 = sn(null);
        final var $t2 = java.util.Objects.isNull($t1) ? ts(null) : null;
        if ($t2 != null && $t2.isErr()) return Result.propagate($t2);
        return new Ok<>(Nulls.<String>or((java.util.Objects.nonNull($t1) ? $t1 : Result.<@Nullable String>unwrap(java.util.Objects.requireNonNull($t2))), () -> "s"));
    }
    public static void main(String[] args) {
        final var c = System.nanoTime() > 0;
        final int y = Nulls.<Integer>or(((c ? Nulls.<@Nullable Integer>or(n(3), () -> n(4)) : Integer.valueOf(42))), () -> Nulls.sure(n(5)));
        var x = 10;
        x += Nulls.<Integer>or(((c ? Nulls.<@Nullable Integer>or(n(null), () -> n(null)) : Integer.valueOf(42))), () -> f());
        System.out.println(y);
        System.out.println(x);
        System.out.println(tried());
        System.out.println(text());
    }
}
