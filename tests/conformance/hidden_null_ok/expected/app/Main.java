package app;
import axl.lang.*;
import org.jspecify.annotations.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.function.Supplier;

@NullMarked public final class Main {
    private Main() {}
    public static @Nullable String maybe() {
        return null;
    }
    public static void main(String[] args) {
        final var xs = new ArrayList<@Nullable String>();
        xs.add(null);
        xs.add("ab");
        System.out.println(Nulls.first(xs.stream().iterator()));
        System.out.println(xs.stream().filter(it -> it != null).map(Nulls.nonNull(it -> it.length())).toList());
        System.out.println(xs.stream().map(Nulls.nonNull(it -> Nulls.or(it, () -> "-"))).sorted().toList());
        final Supplier<@Nullable String> lazy = () -> maybe();
        System.out.println(lazy.get());
        final var m = new HashMap<String, @Nullable String>();
        m.put("k", null);
        System.out.println(m.computeIfAbsent("j", k -> maybe()));
        final @Nullable String text = maybe();
        final int n = Nulls.or(Nulls.map(text, $n1 -> $n1.length()), () -> 0);
        final Supplier<@Nullable Integer> length = () -> Nulls.map(text, $n2 -> $n2.length());
        System.out.println(n);
        System.out.println(length.get());
        for0();
    }
    public static void for0() {
        final var s = maybe();
        if (s != null) {
            final Supplier<String> f = () -> s;
            System.out.println(f.get());
        }
    }
}
