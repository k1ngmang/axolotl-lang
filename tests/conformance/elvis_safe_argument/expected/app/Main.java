package app;
import axl.lang.*;
import org.jspecify.annotations.*;

@NullMarked public final class Main {
    private Main() {}
    public static String shout(String n) {
        return n + "!";
    }
    public static Result<Integer, String> length(@Nullable String s, int k) {
        return new Ok<>(Nulls.present(Nulls.or(Nulls.map(s, $n1 -> $n1.length()), () -> k)).value());
    }
    public static void main(String[] args) {
        final @Nullable String s = " a ";
        final @Nullable String none = null;
        System.out.println(shout(Nulls.present(Nulls.or(Nulls.map(s, $n1 -> $n1.trim()), () -> "none")).value()));
        System.out.println(shout(Nulls.present(Nulls.or(Nulls.map(none, $n2 -> $n2.trim()), () -> "none")).value()));
        final var $t1 = Nulls.map(none, $n3 -> $n3.length());
        System.out.println(Integer.valueOf(Nulls.or($t1, 3)));
        System.out.println(length(null, 4));
    }
}
