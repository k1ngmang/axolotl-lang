package app;
import axl.lang.*;
import org.jspecify.annotations.*;
import java.util.List;

@NullMarked public final class Main {
    private Main() {}
    public sealed interface Delta {
        List<String> lines();
        int position();
    }
    public record Insert(int position, List<String> lines) implements Delta {
        public Insert {
            Nulls.param(lines, "lines");
        }
    }
    public record Delete(int position, List<String> lines) implements Delta {
        public Delete {
            Nulls.param(lines, "lines");
        }
    }
    public interface Keyed<K extends @Nullable Object> {
        K key();
        @Nullable String label();
    }
    public record Entry(String key, String label) implements Keyed<String> {
        public Entry {
            Nulls.param(key, "key");
            Nulls.param(label, "label");
        }
    }
    public static int size(Delta d) {
        Nulls.param(d, "d");
        switch (d) {
            case Insert d$1 -> {
                return d$1.lines().size();
            }
            case Delete d$2 -> {
                return -d$2.lines().size();
            }
        }
    }
    public static String describe(Keyed<String> k) {
        Nulls.param(k, "k");
        return k.key().toUpperCase() + " " + k.label();
    }
    public static void main(String[] args) {
        final List<Delta> deltas = List.<Delta>of(new Insert(0, List.of("a", "b")), new Delete(3, List.of("c")));
        for (final var d : deltas) {
            System.out.println("" + d.position() + ": " + size(d));
        }
        System.out.println(describe(new Entry("key", "answer")));
    }
}
