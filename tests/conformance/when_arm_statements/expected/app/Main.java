package app;
import axl.lang.*;
import org.jspecify.annotations.*;
import java.util.ArrayList;
import java.util.List;

@NullMarked public final class Main {
    private Main() {}
    public static final class Counter {
        public int count;
        public Counter(int count) {
            this.count = count;
        }
    }
    public static final class Spawner {
        public final ArrayList<String> names = new ArrayList<String>();
        public void spawn(Character c) {
            switch (c) {
                case 'r' -> {
                    names.add("rat");
                }
                case 'o' -> {
                    names.add("orc");
                }
                default -> {
                    return;
                }
            }
            System.out.println("spawned " + names.size());
        }
    }
    public static String describe(int n) {
        switch (n) {
            case 1 -> {
                return "one";
            }
            case 2 -> {
                return "two";
            }
        }
        return "many";
    }
    public static void main(String[] args) {
        var n = 0;
        loop$1: for (final var x : List.of(1, 2, 3, 4, 5)) {
            switch (Nulls.box(x)) {
                case 2 -> {
                    continue;
                }
                case 4 -> {
                    break loop$1;
                }
                case null, default -> {
                    n += x;
                }
            }
            System.out.println("added " + x);
        }
        System.out.println(n);
        var s = "";
        final var c = new Counter(0);
        for (final var w : List.of("a", "bb", "ccc")) {
            switch (Nulls.box(w.length())) {
                case 1 -> {
                    s = w;
                }
                case null, default -> {
                    s += w;
                }
            }
            switch (w) {
                case "bb" -> {
                    c.count += 10;
                }
                case null, default -> {
                    c.count += 1;
                }
            }
        }
        System.out.println(s + " " + c.count);
        final var spawner = new Spawner();
        spawner.spawn('r');
        spawner.spawn('#');
        spawner.spawn('o');
        System.out.println(describe(1) + " " + describe(2) + " " + describe(3));
    }
}
