package app;
import axl.lang.*;
import org.jspecify.annotations.*;
import java.util.List;

@NullMarked public final class Main {
    private Main() {}
    public static int number(int v) {
        return v;
    }
    public static @Nullable Integer maybe() {
        return 3;
    }
    public static Result<Integer, String> checked(int v) {
        return new Ok<>(v);
    }
    public static boolean same(@Nullable Integer a, @Nullable Integer b) {
        return ((int) Nulls.<Integer>or(a, () -> number(1000))) == ((int) Nulls.<Integer>or(b, () -> number(1000)));
    }
    public static Result<Boolean, String> tried() {
        final var $t1 = checked(1000);
        if ($t1.isErr()) return Result.propagate($t1);
        final var $t2 = checked(1000);
        if ($t2.isErr()) return Result.propagate($t2);
        return new Ok<>(((int) Result.unwrap($t1)) == ((int) Result.unwrap($t2)));
    }
    public static void main(String[] args) {
        System.out.println(same(null, null));
        System.out.println(same(1, 2));
        System.out.println(tried());
        final var xs = List.of(5);
        final var v = Nulls.or((Nulls.first(xs.stream().filter(it -> it > 100).iterator())), () -> Nulls.sure(maybe()));
        System.out.println(v + 1);
        final @Nullable String s = null;
        System.out.println(Nulls.<String>or(s, () -> Nulls.<String>or(s, () -> Nulls.<String>or(s, () -> Nulls.<String>or(s, () -> Nulls.<String>or(s, () -> Nulls.<String>or(s, () -> Nulls.<String>or(s, () -> Nulls.<String>or(s, () -> Nulls.<String>or(s, () -> Nulls.<String>or(s, () -> Nulls.<String>or(s, () -> Nulls.<String>or(s, () -> Nulls.<String>or(s, () -> Nulls.<String>or(s, () -> Nulls.<String>or(s, () -> Nulls.<String>or(s, () -> Nulls.<String>or(s, () -> Nulls.<String>or(s, () -> "deep")))))))))))))))))));
        final var objects = new java.util.HashMap<String, Object>();
        final @Nullable String t = "x";
        System.out.println(Nulls.or(objects.get("a"), () -> Nulls.sure(t)));
    }
}
