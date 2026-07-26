package app;
import axl.lang.*;
import org.jspecify.annotations.*;

@NullMarked public final class Main {
    private Main() {}
    public static final class Box implements Sized {
        public final int size;
        public Box(int size) {
            this.size = size;
        }
        @Override public int size() { return size; }
    }
    public static final class Crate implements Sized {
        public final int count;
        public Crate(int count) {
            this.count = count;
        }
        @Override public int size() {
            return count;
        }
    }
    public record Bag(int size) implements Sized {}
    public static void main(String[] args) {
        final java.util.List<Sized> sized = java.util.List.of(new Box(1), new Crate(2), new Bag(3));
        var total = 0;
        for (final var s : sized) {
            total += s.size();
        }
        System.out.println(total);
    }
}
