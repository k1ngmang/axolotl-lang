package app;
import axl.lang.*;
import org.jspecify.annotations.*;

@NullMarked public final class Main {
    private Main() {}
    public static final class Box {
        public final String value;
        public int seen;
        public Box(String value) {
            this.value = value;
            this.seen = 0;
        }
        public String read() {
            seen += 1;
            return value;
        }
    }
    public static void main(String[] args) {
        final var counter = Counter.start("c");
        counter.step = 5;
        System.out.println(counter.next());
        System.out.println(counter.twice());
        System.out.println(counter.describe());
        counter.reset();
        System.out.println(counter.describe());
        final var box = new Box("x");
        System.out.println(box.read() + box.read());
        System.out.println(box.seen);
    }
}
