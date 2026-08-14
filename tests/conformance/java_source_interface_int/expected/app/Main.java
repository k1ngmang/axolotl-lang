package app;
import axl.lang.*;
import org.jspecify.annotations.*;

@NullMarked public final class Main {
    private Main() {}
    public static final class Counter implements Feed<Integer> {
        public int last = 0;
        public Integer next() {
            return last;
        }
        public void push(Integer item) {
            last = item;
        }
    }
    public static void main(String[] args) {
        final var counter = new Counter();
        counter.push(3);
        System.out.println(counter.next());
    }
}
