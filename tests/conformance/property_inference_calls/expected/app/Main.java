package app;
import axl.lang.*;
import org.jspecify.annotations.*;
import java.util.List;

@NullMarked public final class Main {
    private Main() {}
    public static final class Timer {
        public final String label;
        public final long started;
        public final String upper;
        public final List<String> parts;
        public final int size;
        public long elapsed;
        public Timer(String label) {
            this.label = label;
            this.started = System.nanoTime();
            this.upper = label.toUpperCase();
            this.parts = List.of(label);
            this.size = label.length();
            this.elapsed = started - started;
        }
        public String show() {
            return upper + " " + parts.size() + " " + size + " " + (System.nanoTime() >= started) + " " + elapsed;
        }
    }
    public static void main(String[] args) {
        System.out.println(new Timer("label").show());
    }
}
