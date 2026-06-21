package app;
import axl.lang.*;
import org.jspecify.annotations.*;

@NullMarked public final class Main {
    private Main() {}
    public static void main(String[] args) {
        final @Nullable String a0 = "x";
        final @Nullable String a1 = null;
        final @Nullable String a2 = null;
        final @Nullable String a3 = "y";
        final var r = (a3 != null ? ((a2 != null ? ((a1 != null ? ((a0 != null ? ((a3 != null ? ((a2 != null ? ((a1 != null ? ((a0 != null ? ((a3 != null ? ((a2 != null ? ((a1 != null ? ((a0 != null ? ((a3 != null ? ((a2 != null ? ((a1 != null ? ((a0 != null ? (a0) : a1)) : a2)) : a3)) : a0)) : a1)) : a2)) : a3)) : a0)) : a1)) : a2)) : a3)) : a0)) : a1)) : a2)) : a3)) : a0);
        System.out.println(r);
    }
}
