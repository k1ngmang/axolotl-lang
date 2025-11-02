package app;
import axl.lang.*;
import org.jspecify.annotations.*;

@NullMarked public final class Inline {
    private Inline() {}
    public static String fallback() {
        System.out.println("fallback evaluated");
        return "none";
    }
    public static String name(@Nullable String s) {
        final var $t1 = Nulls.map(s, $n1 -> $n1.trim());
        return java.util.Objects.nonNull($t1) ? $t1 : fallback();
    }
    public static void main(String[] args) {
        System.out.println(name(" axl "));
        System.out.println(name(null));
        var count = 1;
        final @Nullable Integer boxed = null;
        final var $t1 = boxed;
        final int n = java.util.Objects.nonNull($t1) ? $t1 : count;
        count = n + 1;
        System.out.println(count);
    }
}
