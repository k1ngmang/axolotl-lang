package app;
import axl.lang.*;
import org.jspecify.annotations.*;
import java.util.Map;

@NullMarked public final class Main {
    private Main() {}
    public static int f() {
        return 1000;
    }
    public static Result<Integer, String> q(int n) {
        return new Ok<>(n);
    }
    public static @Nullable Integer n() {
        return 1000;
    }
    public static final class C {
        public final @Nullable Integer a;
        public final @Nullable Integer b;
        public C(@Nullable Integer a, @Nullable Integer b) {
            this.a = a;
            this.b = b;
        }
        public boolean same() {
            return Nulls.same(Nulls.or(a, () -> f()), Nulls.or(b, () -> f()));
        }
    }
    public static Result<Boolean, String> tried() {
        final var $t1 = q(1000);
        if ($t1.isErr()) return Result.propagate($t1);
        final var x = Result.unwrap($t1);
        final var $t2 = q(1000);
        if ($t2.isErr()) return Result.propagate($t2);
        final var y = Result.unwrap($t2);
        boolean $t3 = false;
        if (((int) x) == ((int) y)) {
            final var $t4 = q(1000);
            if ($t4.isErr()) return Result.propagate($t4);
            final var $t5 = q(1000);
            if ($t5.isErr()) return Result.propagate($t5);
            $t3 = ((int) Result.unwrap($t4)) == ((int) Result.unwrap($t5));
        }
        return new Ok<>($t3);
    }
    public static void main(String[] args) {
        final Map<String, Integer> m = Map.of();
        final @Nullable String s = null;
        System.out.println(((int) Nulls.<Integer>or(m.get("a"), () -> f())) == ((int) Nulls.<Integer>or(m.get("b"), () -> f())));
        System.out.println(!Nulls.same(Nulls.or(Nulls.map(s, $n1 -> $n1.length()), () -> f()), Nulls.or(Nulls.map(s, $n2 -> $n2.length()), () -> f())));
        System.out.println(new C(null, null).same());
        System.out.println(tried());
        final @Nullable Integer a = null;
        final var x = Nulls.<Integer>or(a, () -> f());
        final var y = Nulls.<Integer>or(a, () -> f());
        System.out.println(((int) x) == ((int) y));
        System.out.println(((int) java.util.Objects.requireNonNull(n())) == ((int) Nulls.<Integer>or(a, () -> f())));
        System.out.println(Nulls.same(Nulls.<String>or(s, () -> "x"), Nulls.<String>or(s, () -> "x")));
    }
}
