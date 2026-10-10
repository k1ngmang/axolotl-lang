package app;
import axl.lang.*;
import org.jspecify.annotations.*;
import java.util.HashMap;
import java.util.Map;

@NullMarked public final class Main {
    private Main() {}
    public static String lookup(Map<String, String> m, String key) {
        Nulls.param(m, "m");
        Nulls.param(key, "key");
        return Nulls.or(m.get(key), () -> { throw new IllegalArgumentException("no entry for " + key); });
    }
    public static int lengthOf(@Nullable String s) {
        final var text = Nulls.or(s, () -> { throw new IllegalStateException("missing"); });
        return text.length();
    }
    public static String first(Map<String, String> m, String a, String b) {
        Nulls.param(m, "m");
        Nulls.param(a, "a");
        Nulls.param(b, "b");
        return Nulls.or(Nulls.<@Nullable String>or(m.get(a), () -> m.get(b)), () -> { throw new IllegalArgumentException("neither " + a + " nor " + b); });
    }
    public static void main(String[] args) {
        final var m = new HashMap<String, String>();
        m.put("a", "alpha");
        System.out.println(lookup(m, "a"));
        System.out.println(lengthOf("four"));
        System.out.println(first(m, "x", "a"));
        System.out.println(Result.attempt(() -> lookup(m, "z"), e -> e.getMessage()));
        System.out.println(Result.attempt(() -> first(m, "x", "y"), e -> e.getMessage()));
        System.out.println(Result.attempt(() -> lengthOf(null), e -> e.getMessage()));
    }
}
