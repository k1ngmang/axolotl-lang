package app;
import axl.lang.*;
import org.jspecify.annotations.*;

@NullMarked public final class Main {
    private Main() {}
    public static @Nullable Long maybeLong() {
        return null;
    }
    public static @Nullable Double maybeDouble() {
        return null;
    }
    public static @Nullable Integer maybeInt() {
        return 7;
    }
    public static void main(String[] args) {
        final long a = Nulls.or(maybeLong(), 0);
        final double b = Nulls.or(maybeDouble(), -1);
        final var c = Nulls.or(maybeInt(), 0);
        final long d = Nulls.or(maybeLong(), 5L);
        System.out.println(a);
        System.out.println(b);
        System.out.println(c);
        System.out.println(d);
    }
}
