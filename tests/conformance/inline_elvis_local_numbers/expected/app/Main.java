package app;
import axl.lang.*;
import org.jspecify.annotations.*;

@NullMarked public final class Main {
    private Main() {}
    public static @Nullable Object pick(@Nullable Integer a, @Nullable Long b) {
        final var c = Nulls.or(a, () -> b);
        return c;
    }
    public static void main(String[] args) {
        System.out.println(pick(null, null));
        final @Nullable Integer a = 5;
        final var x = Nulls.or(a, () -> 1.5);
        System.out.println(x);
        var k = true;
        k = !k;
        final @Nullable Boolean flag = null;
        final var $c1 = k;
        if (Nulls.<Boolean>or(flag, () -> $c1)) {
            System.out.println("yes");
        }
        final @Nullable String name = null;
        final var $t1 = name;
        final var shown = java.util.Objects.nonNull($t1) ? $t1 : "k=" + k;
        System.out.println(shown);
        numbers();
    }
    public static void numbers() {
        final @Nullable Integer x = null;
        System.out.println(Nulls.or(x, () -> 1L));
        System.out.println(Nulls.or(x, () -> 1.5));
        final @Nullable Short s = null;
        final int y = Nulls.or(s, () -> (short) 0);
        System.out.println(y);
        final @Nullable Float f = null;
        System.out.println(Nulls.or(f, 0));
        final @Nullable Long l = null;
        System.out.println(Nulls.or(l, () -> 1.5));
    }
}
