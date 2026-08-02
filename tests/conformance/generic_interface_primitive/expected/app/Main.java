package app;
import axl.lang.*;
import org.jspecify.annotations.*;
import java.util.HashMap;

@NullMarked public final class Main {
    private Main() {}
    public interface Repository<K extends @Nullable Object, V extends @Nullable Object> {
        @Nullable V find(K key);
        void save(K key, V value);
        boolean same(V a, V b);
        V total();
    }
    public interface Scalable<T extends @Nullable Object> {
        Scalable<T> scale(T factor);
    }
    public static final class Ages implements Repository<String, Integer> {
        public final HashMap<String, Integer> items = new HashMap<String, Integer>();
        @Override public @Nullable Integer find(String key) {
            return items.get(key);
        }
        @Override public void save(String key, Integer value) {
            items.put(key, value);
        }
        @Override public boolean same(Integer a, Integer b) {
            return ((int) a) == ((int) b);
        }
        @Override public Integer total() {
            var sum = 0;
            for (final var v : items.values()) {
                sum += v;
            }
            return sum;
        }
    }
    public record Vec(double x) implements Scalable<Double> {
        @Override public Scalable<Double> scale(Double factor) {
            return new Vec(x * factor);
        }
    }
    public static void main(String[] args) {
        final var ages = new Ages();
        ages.save("ann", 1000);
        ages.save("bob", 1000);
        final Repository<String, Integer> repository = ages;
        System.out.println(Nulls.or(repository.find("ann"), -1));
        System.out.println(Nulls.or(repository.find("cid"), -1));
        System.out.println(repository.same(1000, 1000));
        System.out.println(ages.same(Nulls.or(ages.find("ann"), 0), Nulls.or(ages.find("bob"), 0)));
        System.out.println(repository.total());
        System.out.println(new Vec(1.5).scale(2.0));
    }
}
