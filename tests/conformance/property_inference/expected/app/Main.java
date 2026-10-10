package app;
import axl.lang.*;
import org.jspecify.annotations.*;
import java.util.ArrayList;
import java.util.HashMap;

@NullMarked public final class Main {
    private Main() {}
    public static final class Counter {
        public final String name;
        public final ArrayList<String> items;
        public final HashMap<String, Integer> seen;
        public int count;
        public String label;
        public double scale;
        public Counter(String name) {
            Nulls.param(name, "name");
            this.name = name;
            this.items = new ArrayList<String>();
            this.seen = new HashMap<String, Integer>();
            this.count = 0;
            this.label = "none";
            this.scale = 1.5;
        }
        public void add(String item) {
            Nulls.param(item, "item");
            items.add(item);
            seen.put(item, items.size());
            count += 1;
            label = item;
        }
    }
    public static void main(String[] args) {
        final var c = new Counter("c");
        c.add("x");
        c.add("y");
        System.out.println(c.items.size() + " " + c.count + " " + c.label + " " + Nulls.or(c.seen.get("y"), 0) + " " + c.scale);
    }
}
