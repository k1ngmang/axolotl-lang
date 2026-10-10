package app;
import axl.lang.*;
import org.jspecify.annotations.*;
import java.util.List;

@NullMarked public record Chunk(int position, List<String> lines, @Nullable String origin) {
    public Chunk {
        Nulls.param(lines, "lines");
    }
    public Chunk shifted(int by, String reason) {
        Nulls.param(reason, "reason");
        return new Chunk(position + by, lines, reason);
    }
    public record Box<T extends @Nullable Object>(T value, String label) {
        public Box {
            Nulls.param(label, "label");
        }
    }
    public record Ranked<T extends Comparable<T>>(T value) {
        public Ranked {
            Nulls.param(value, "value");
        }
    }
    @SuppressWarnings("EffectivelyPrivate") private record Pair(String left, String right) {}
    public static int last(Chunk c) {
        Nulls.param(c, "c");
        return c.position + c.lines.size() - 1;
    }
    public static <T extends @Nullable Object> T first(List<T> items, T fallback) {
        Nulls.param(items, "items");
        return (items.isEmpty() ? fallback : items.get(0));
    }
    public static <T extends Comparable<T>> T largest(T a, T b) {
        Nulls.param(a, "a");
        Nulls.param(b, "b");
        return (a.compareTo(b) >= 0 ? a : b);
    }
    public static String joined(String a, @Nullable String b, long count, boolean flag, double ratio) {
        Nulls.param(a, "a");
        return secret(a) + b + count + flag + ratio;
    }
    private static String secret(String text) {
        return new Pair(text, text).left();
    }
}
