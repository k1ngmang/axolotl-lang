package app;
import axl.lang.*;
import org.jspecify.annotations.*;

@NullMarked public final class Main {
    private Main() {}
    public static Result<Integer, String> one(String s) {
        Nulls.param(s, "s");
        return (s.isEmpty() ? new Err<>("empty") : new Ok<>(s.length()));
    }
    public static String describe(int n) {
        return "n=" + n;
    }
    public static final class Box {
        public final int v;
        public final @Nullable String text;
        public final int size;
        public Box(int v, @Nullable String text) {
            this.v = v;
            this.text = text;
            final var $t1 = Nulls.map(text, $n1 -> $n1.length());
            this.size = Nulls.or($t1, 0);
        }
        public String describe() {
            return Main.describe(v);
        }
        public Result<java.util.List<Integer>, String> parse(java.util.List<String> xs) {
            Nulls.param(xs, "xs");
            final java.util.List<Integer> $p1 = new java.util.ArrayList<>();
            for (final var it : xs) {
                final var $t1 = item(it);
                if ($t1.isErr()) return Result.propagate($t1);
                $p1.add(Result.<Integer>unwrap($t1));
            }
            final var sizes = $p1;
            return new Ok<>(sizes);
        }
        public Result<Integer, String> item(String s) {
            Nulls.param(s, "s");
            return one(s);
        }
    }
    public static String label(int k, @Nullable String s) {
        var count = 1;
        count += 1;
        switch (k) {
            case 1 -> {
                final var $c1 = count;
                return Nulls.<String>or(s, () -> "count=" + $c1);
            }
            default -> {
                return "other";
            }
        }
    }
    public static void main(String[] args) {
        final var box = new Box(1, "abc");
        System.out.println(box.describe() + " " + box.size);
        System.out.println(box.parse(java.util.List.of("a", "bb")));
        System.out.println(label(1, null));
        System.out.println(Color.warm(Color.RED));
    }
}
