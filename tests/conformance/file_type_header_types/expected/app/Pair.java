package app;
import axl.lang.*;
import org.jspecify.annotations.*;
import java.util.function.Supplier;

@NullMarked public record Pair<T extends Comparable<Pair.Part>>(Part part, @Nullable T other) implements Supplier<Pair.Part> {
    public Pair {
        Nulls.param(part, "part");
    }
    public Part get() {
        return part;
    }
    public record Part(int n) implements Comparable<Part> {
        public int compareTo(Part other) {
            Nulls.param(other, "other");
            return Integer.compare(n, other.n());
        }
    }
}
