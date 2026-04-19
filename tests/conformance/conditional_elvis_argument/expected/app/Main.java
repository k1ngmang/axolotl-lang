package app;
import axl.lang.*;
import org.jspecify.annotations.*;

@NullMarked public final class Main {
    private Main() {}
    public static String shout(String s) {
        return s + "!";
    }
    public static void main(String[] args) {
        final @Nullable String s = null;
        final var c = System.nanoTime() > 0;
        System.out.println(Nulls.present((c ? Nulls.or(Nulls.map(s, $n1 -> $n1.length()), () -> 1000) : (false ? 42 : 7))).value());
        System.out.println(shout(Nulls.present((c ? Nulls.or(Nulls.map(s, $n2 -> $n2.trim()), () -> "none") : "other")).value()));
    }
}
