package app;
import axl.lang.*;
import org.jspecify.annotations.*;
import java.util.function.Supplier;

@NullMarked public final class Main {
    private Main() {}
    public static final class Counter {
        public final String text;
        public final Supplier<Integer> size;
        public final String later;
        public Counter(String text) {
            Nulls.param(text, "text");
            this.text = text;
            this.size = this::count;
            this.later = text + "!";
        }
        public int count() {
            return later.length();
        }
    }
    public static final class Box {
        public int size() {
            return early.length();
        }
    }
    public static final String early = "ab".trim();
    public static final int sizes = new Box().size() + java.util.List.of(new Box()).get(0).size();
    public static void main(String[] args) {
        System.out.println(new Counter("a").size.get());
        System.out.println(sizes);
    }
}
