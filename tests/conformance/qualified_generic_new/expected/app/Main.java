package app;
import axl.lang.*;
import org.jspecify.annotations.*;

@NullMarked public final class Main {
    private Main() {}
    public static final class Item {
        public final String name;
        public Item(String name) {
            Nulls.param(name, "name");
            this.name = name;
        }
    }
    public static void main(String[] args) {
        final var counts = new java.util.HashMap<String, Integer>();
        counts.put("a", 1);
        final var items = new java.util.ArrayList<Main.Item>();
        items.add(new Item("x"));
        final var names = new java.util.TreeSet<String>(counts.keySet());
        System.out.println(counts.get("a"));
        System.out.println(items.get(0).name);
        System.out.println(names);
    }
}
