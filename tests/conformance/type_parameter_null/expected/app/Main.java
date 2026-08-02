package app;
import axl.lang.*;
import org.jspecify.annotations.*;
import java.util.List;

@NullMarked public final class Main {
    private Main() {}
    public record Box<T extends @Nullable Object>(T value) {}
    public static <T extends @Nullable Object> String shown(T x) {
        if (x == null) {
            return "null";
        }
        return x.toString();
    }
    public static <T extends @Nullable Object> String shownOr(T x, String fallback) {
        return Nulls.or(Nulls.map(x, $n1 -> $n1.toString()), () -> fallback);
    }
    public static <T> String plain(T x) {
        return x.toString();
    }
    public static <T extends @Nullable Object> String unbox(Box<T> b) {
        return Nulls.or(Nulls.map(b.value(), $n1 -> $n1.toString()), () -> "empty");
    }
    public static <T extends @Nullable Object> String first(List<T> xs) {
        final var x = xs.get(0);
        return (x != null ? x.toString() : "none");
    }
    public static final class Holder<T extends @Nullable Object> {
        public final T item;
        public Holder(T item) {
            this.item = item;
        }
        public String shown() {
            return Nulls.or(Nulls.map(item, $n1 -> $n1.toString()), () -> "nothing");
        }
    }
    public static void main(String[] args) {
        final @Nullable String maybe = null;
        System.out.println(shown(maybe));
        System.out.println(shown("a"));
        System.out.println(shownOr(maybe, "-"));
        System.out.println(shownOr(3, "-"));
        System.out.println(plain("b"));
        System.out.println(unbox(new Box<@Nullable String>(null)));
        System.out.println(unbox(new Box<Integer>(4)));
        System.out.println(first(java.util.Arrays.asList(maybe)));
        System.out.println(new Holder<@Nullable String>(null).shown());
        System.out.println(new Holder<String>("c").shown());
    }
}
