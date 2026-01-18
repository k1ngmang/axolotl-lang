package app;
import axl.lang.*;
import org.jspecify.annotations.*;

@NullMarked public final class Main {
    private Main() {}
    public static void main(String[] args) {
        final var s = "abc";
        final var n = Nulls.or(s.length(), 0);
        final @Nullable String maybe = null;
        final var $t1 = maybe;
        final var m = java.util.Objects.nonNull($t1) ? $t1 : "none";
        System.out.println(n);
        System.out.println(m);
    }
}
