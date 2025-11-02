package app;
import axl.lang.*;
import org.jspecify.annotations.*;

@NullMarked public final class Main {
    private Main() {}
    public static void main(String[] args) {
        final var s = "abc";
        final var $t1 = s.length();
        final var n = java.util.Objects.nonNull($t1) ? $t1 : 0;
        final @Nullable String maybe = null;
        final var $t2 = maybe;
        final var m = java.util.Objects.nonNull($t2) ? $t2 : "none";
        System.out.println(n);
        System.out.println(m);
    }
}
