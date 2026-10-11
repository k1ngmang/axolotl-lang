package app;
import axl.lang.*;
import org.jspecify.annotations.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@NullMarked public final class Main {
    private Main() {}
    public record Pair<A extends @Nullable Object, B extends @Nullable Object>(A first, B second) {}
    public sealed interface Option<T extends @Nullable Object> {
        boolean present();
    }
    public record Some<T extends @Nullable Object>(boolean present, T value) implements Option<T> {}
    public record None<T extends @Nullable Object>(boolean present) implements Option<T> {}
    public interface Repository<K extends @Nullable Object, V extends @Nullable Object> {
        String name();
        @Nullable V find(K key);
        void save(K key, V value);
    }
    public static final class MemoryRepository<K extends @Nullable Object, V extends @Nullable Object> implements Repository<K, V> {
        public final String name;
        public final Map<K, V> items;
        public MemoryRepository(String name) {
            Nulls.param(name, "name");
            this.name = name;
            this.items = new HashMap<K, V>();
        }
        @Override public @Nullable V find(K key) {
            return items.get(key);
        }
        @Override public void save(K key, V value) {
            items.put(key, value);
        }
        @Override public String name() { return name; }
    }
    public static final class Stack<T> {
        public final List<T> items = new ArrayList<T>();
        public void push(T item) {
            Nulls.param(item, "item");
            items.add(item);
        }
        public @Nullable T pop() {
            return (items.isEmpty() ? null : items.remove(items.size() - 1));
        }
        public String describe() {
            final var top = Nulls.first(items.stream().map(Nulls.nonNull(it -> it.toString())).iterator());
            return items.size() + " items, top " + Nulls.or(top, () -> "none");
        }
    }
    public static <T extends @Nullable Object> @Nullable T firstOrNull(List<T> xs) {
        Nulls.param(xs, "xs");
        return (xs.isEmpty() ? null : xs.get(0));
    }
    public static <T extends Comparable<T>> @Nullable T largest(List<T> xs) {
        Nulls.param(xs, "xs");
        @Nullable T best = null;
        for (final var x : xs) {
            if (best == null || x.compareTo(best) > 0) {
                best = x;
            }
        }
        return best;
    }
    public static <A extends @Nullable Object, B extends @Nullable Object> Pair<B, A> swap(Pair<A, B> p) {
        Nulls.param(p, "p");
        return new Pair<B, A>(p.second(), p.first());
    }
    public static <T extends @Nullable Object> T valueOr(Option<T> option, T fallback) {
        Nulls.param(option, "option");
        switch (option) {
            case Some<T> option$1 -> {
                return option$1.value();
            }
            case None<T> option$2 -> {
                return fallback;
            }
        }
    }
    public static void main(String[] args) {
        final var pair = new Pair<String, Integer>("age", 41);
        System.out.println(swap(pair));
        final var swapped = swap(pair);
        System.out.println(swapped.first() + 1);
        final var repo = new MemoryRepository<String, Integer>("ages");
        repo.save("ann", 30);
        System.out.println(repo.find("ann"));
        System.out.println(repo.find("bob"));
        System.out.println(repo.name);
        final var stack = new Stack<String>();
        stack.push("a");
        stack.push("b");
        System.out.println(stack.describe());
        System.out.println(stack.pop());
        System.out.println(firstOrNull(List.of(3, 4)));
        final List<String> empty = List.of();
        System.out.println(firstOrNull(empty));
        System.out.println(largest(List.of(3, 9, 4)));
        System.out.println(largest(List.of("pear", "apple")));
        final Option<String> some = new Some<String>(true, "x");
        final Option<String> none = new None<String>(false);
        System.out.println(valueOr(some, "y") + valueOr(none, "y"));
        final Pair<String, @Nullable Integer> maybe = new Pair<String, @Nullable Integer>("none", null);
        System.out.println(Nulls.or(maybe.second(), -1));
    }
}
