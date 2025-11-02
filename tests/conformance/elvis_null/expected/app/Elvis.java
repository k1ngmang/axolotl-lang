package app;
import axl.lang.*;
import org.jspecify.annotations.*;

@NullMarked public final class Elvis {
    private Elvis() {}
    public static String fallback() {
        System.out.println("fallback evaluated");
        return "x";
    }
    public static void main(String[] args) {
        final var a = Nulls.or(null, () -> "x");
        System.out.println(a);
        final @Nullable String b = "set";
        System.out.println(Nulls.or(b, () -> fallback()));
        final @Nullable String c = null;
        System.out.println(Nulls.or(c, () -> fallback()));
        final @Nullable Integer d = null;
        final int e = Nulls.or(d, 7);
        System.out.println(e + 1);
    }
}
