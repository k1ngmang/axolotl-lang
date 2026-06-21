package app;
import axl.lang.*;
import org.jspecify.annotations.*;

@NullMarked public final class Main {
    private Main() {}
    public static long orZero(@Nullable Long x) {
        return Nulls.or(x, 0L);
    }
    public static double half(@Nullable Double x) {
        return Nulls.or(x, 0.0) / 2;
    }
    public static @Nullable Long seven() {
        return 7L;
    }
    public static void main(String[] args) {
        final @Nullable Long a = 5L;
        final @Nullable Double b = -3.0;
        System.out.println(a);
        System.out.println(b);
        System.out.println(orZero(5L) + orZero(null));
        System.out.println(half(3.0));
        System.out.println(seven());
    }
}
