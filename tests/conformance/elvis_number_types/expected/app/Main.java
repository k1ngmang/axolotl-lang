package app;
import axl.lang.*;
import org.jspecify.annotations.*;

@NullMarked public final class Main {
    private Main() {}
    public static @Nullable Number number() {
        return null;
    }
    public static @Nullable Object anything() {
        return null;
    }
    public static @Nullable String text() {
        return null;
    }
    public static void main(String[] args) {
        final Number n = Nulls.or(number(), () -> 0);
        System.out.println(n);
        System.out.println(Nulls.or(anything(), () -> 1.5));
        final var $t1 = Nulls.map(text(), $n1 -> Nulls.map($n1.chars(), $n2 -> $n2.count()));
        final var count = Nulls.or($t1, 0);
        System.out.println(count + 1);
        final var $t2 = Nulls.map(text(), $n3 -> $n3.length());
        System.out.println(Nulls.or($t2, -1));
        final String s = Nulls.or(text(), () -> "none");
        System.out.println(s);
    }
}
