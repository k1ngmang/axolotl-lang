package app;
import axl.lang.*;
import org.jspecify.annotations.*;
import java.util.List;

@NullMarked public final class Keys {
    private Keys() {}
    public interface Keyed<K extends @Nullable Object> {
        K key();
    }
    public interface Named<K extends @Nullable Object> {
        K key();
    }
    public interface Bounds<T extends @Nullable Object> {
        T low();
        T high();
    }
    public interface IntKeyed extends Keyed<Integer> {}
    public record Entry(Integer key, String label) implements Keyed<Integer> {
        public Entry {
            Nulls.param(key, "key");
            Nulls.param(label, "label");
        }
        public boolean sameKey(Entry other) {
            Nulls.param(other, "other");
            return ((int) key) == ((int) other.key());
        }
    }
    public record Tagged(Long key) implements Named<Long> {
        public Tagged {
            Nulls.param(key, "key");
        }
    }
    public record Flag(Boolean key) implements Named<Boolean> {
        public Flag {
            Nulls.param(key, "key");
        }
    }
    public record Weight(Double key) implements Keyed<Double> {
        public Weight {
            Nulls.param(key, "key");
        }
    }
    public record Slot(Integer key) implements IntKeyed {
        public Slot {
            Nulls.param(key, "key");
        }
    }
    public record Range(Integer low, Integer high) implements Bounds<Integer> {
        public Range {
            Nulls.param(low, "low");
            Nulls.param(high, "high");
        }
        public boolean empty() {
            return ((int) low) == ((int) high);
        }
    }
    public static final class Account implements Named<Long> {
        public final long key;
        public final String owner;
        public Account(long key, String owner) {
            Nulls.param(owner, "owner");
            this.key = key;
            this.owner = owner;
        }
        @Override public Long key() { return key; }
    }
    public static final class Counter implements Named<Integer> {
        public int count;
        public Counter(int count) {
            this.count = count;
        }
        @Override public Integer key() {
            return count;
        }
    }
    public static String describe(Entry e) {
        Nulls.param(e, "e");
        switch (e.key()) {
            case 1000 -> {
                return "thousand";
            }
            default -> {
                return "other " + e.key();
            }
        }
    }
    public static int sizeOf(Bounds<Integer> b) {
        Nulls.param(b, "b");
        return b.high() - b.low();
    }
    public static void main(String[] args) {
        final var a = new Entry(1000, "a");
        final var b = new Entry(1000, "a");
        final Keyed<Integer> keyed = a;
        System.out.println(keyed.key() + 1);
        System.out.println(((int) a.key()) == ((int) b.key()));
        System.out.println(a.equals(b));
        System.out.println(a.hashCode() == b.hashCode());
        System.out.println(a.sameKey(b));
        System.out.println(a);
        System.out.println(describe(a));
        System.out.println(describe(new Entry(7, "b")));
        final var first = a.key();
        final var second = b.key();
        System.out.println(((int) first) == ((int) second));
        final Keyed<Integer> other = b;
        System.out.println(((int) keyed.key()) == ((int) other.key()));
        final Named<Long> named = new Tagged(5000000000L);
        System.out.println(named.key() + 1);
        System.out.println(new Flag(true).key() && !new Flag(false).key());
        System.out.println(new Weight(1.5).key() * 2);
        System.out.println(new Slot(3).key() + 1);
        final var range = new Range(1000, 1000);
        System.out.println(range.empty());
        System.out.println(sizeOf(new Range(2, 9)));
        final Named<Long> account = new Account(42L, "ann");
        System.out.println(account.key());
        final var counter = new Counter(2000);
        final var again = new Counter(2000);
        System.out.println(((int) counter.key()) == ((int) again.key()));
        final List<Keyed<Integer>> keys = List.of(a, new Entry(2, "c"));
        System.out.println(keys.stream().map(Nulls.nonNull(k -> k.key() * 10)).toList());
    }
}
