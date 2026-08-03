package app;
import axl.lang.*;
import org.jspecify.annotations.*;
import java.util.Map;

@NullMarked public final class Main {
    private Main() {}
    public static void show(int n) {
        System.out.println(n);
    }
    public static String shout(String s) {
        return s + "!";
    }
    public static void main(String[] args) {
        final @Nullable String s = null;
        final var c = System.nanoTime() > 0;
        System.out.println(Nulls.present((c ? Nulls.<Integer>or(Nulls.map(s, $n1 -> $n1.length()), () -> 1000) : (false ? 42 : 7))).value());
        System.out.println(shout(Nulls.present((c ? Nulls.<String>or(Nulls.map(s, $n2 -> $n2.trim()), () -> "none") : "other")).value()));
        System.out.println(Nulls.present(((c ? Nulls.<Integer>or(Nulls.map(s, $n3 -> $n3.length()), () -> 5) : "ab".length()))).value());
        System.out.println(1 + Nulls.present(((c ? Nulls.<Integer>or(Nulls.map(s, $n4 -> $n4.length()), () -> 5) : "ab".length()))).value());
        System.out.println(Math.max(0, Nulls.present((c ? Nulls.<Integer>or(Nulls.map(s, $n5 -> $n5.length()), () -> 5) : "ab".length())).value()));
        show(Nulls.present((c ? Nulls.<Integer>or(Nulls.map(s, $n6 -> $n6.length()), () -> 5) : "ab".length())).value());
        System.out.println(new Ok<>(Nulls.present(((c ? Nulls.<Integer>or(Nulls.map(s, $n7 -> $n7.length()), () -> 5) : "ab".length()))).value()));
        final Map<String, Integer> m = Map.of();
        System.out.println(Nulls.inferred((!c ? Integer.valueOf(Nulls.<Integer>or(Nulls.map(s, $n8 -> $n8.length()), () -> 5)) : m.get("k"))).value());
    }
}
