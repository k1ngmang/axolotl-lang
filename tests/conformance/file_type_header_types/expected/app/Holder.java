package app;
import axl.lang.*;
import org.jspecify.annotations.*;
import java.util.ArrayList;
import java.util.function.Supplier;

@NullMarked public final class Holder<T extends Comparable<Holder.Item>> extends ArrayList<Holder.Item> implements Supplier<Holder.@Nullable Item> {
    public final @Nullable T first;
    public Holder(@Nullable T first) {
        this.first = first;
    }
    public @Nullable Item get() {
        return (isEmpty() ? null : get(0));
    }
    public static final class Item implements Comparable<Item> {
        public final int n;
        public Item(int n) {
            this.n = n;
        }
        public int compareTo(Item other) {
            Nulls.param(other, "other");
            return Integer.compare(n, other.n);
        }
    }
}
