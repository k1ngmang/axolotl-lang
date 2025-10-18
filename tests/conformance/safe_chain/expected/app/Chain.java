package app;
import axl.lang.*;
import org.jspecify.annotations.*;

@NullMarked public final class Chain {
    private Chain() {}
    public static String suffix() {
        System.out.println("suffix evaluated");
        return "!";
    }
    public static @Nullable String shout(@Nullable String s) {
        return Nulls.map(s, $n1 -> Nulls.map($n1.trim(), $n2 -> $n2.concat(suffix())));
    }
    public static void main(String[] args) {
        System.out.println(shout(null));
        System.out.println(shout(" hi "));
        final @Nullable StringBuilder missing = null;
        final var $n1 = missing;
        if ($n1 != null) {
            $n1.setLength(0);
        }
        final @Nullable StringBuilder builder = new StringBuilder("ab");
        final var $n2 = builder;
        if ($n2 != null) {
            $n2.setLength(1);
        }
        System.out.println(builder);
    }
}
