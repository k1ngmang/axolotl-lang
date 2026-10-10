package app;
import axl.lang.*;
import org.jspecify.annotations.*;
import java.util.ArrayList;
import java.util.List;

@NullMarked public final class Main {
    private Main() {}
    public interface Notes {
        String name();
        int add(String text);
        @Nullable String find(int id);
        void clear();
    }
    public static final class MemoryNotes implements Notes {
        public final String name;
        public final List<String> items;
        public MemoryNotes(String name) {
            Nulls.param(name, "name");
            this.name = name;
            this.items = new ArrayList<String>();
        }
        @Override public int add(String text) {
            Nulls.param(text, "text");
            items.add(text);
            return items.size();
        }
        @Override public @Nullable String find(int id) {
            return (id >= 1 && id <= items.size() ? items.get(id - 1) : null);
        }
        @Override public void clear() {
            items.clear();
        }
        @Override public String name() { return name; }
    }
    public static final class CountingNotes implements Notes {
        public final Notes inner;
        public int added;
        public CountingNotes(Notes inner) {
            Nulls.param(inner, "inner");
            this.inner = inner;
            this.added = 0;
        }
        @Override public String name() {
            return inner.name() + " (counted)";
        }
        @Override public int add(String text) {
            Nulls.param(text, "text");
            added += 1;
            return inner.add(text);
        }
        @Override public @Nullable String find(int id) {
            return inner.find(id);
        }
        @Override public void clear() {
            inner.clear();
        }
    }
    public static String describe(Notes notes) {
        Nulls.param(notes, "notes");
        final var id = notes.add("buy milk");
        final var found = notes.find(id);
        return notes.name() + ": " + id + " " + Nulls.<String>or(found, () -> "nothing") + " " + Nulls.<String>or(notes.find(99), () -> "nothing");
    }
    public static void main(String[] args) {
        final var memory = new MemoryNotes("memory");
        System.out.println(describe(memory));
        final var counting = new CountingNotes(memory);
        System.out.println(describe(counting));
        System.out.println(counting.added);
        counting.clear();
        System.out.println(memory.items.size());
    }
}
