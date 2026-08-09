package app;
import axl.lang.*;
import org.jspecify.annotations.*;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.IntBinaryOperator;
import java.util.function.Predicate;
import java.util.function.Supplier;

@NullMarked public final class Main {
    private Main() {}
    public static final class Doubler implements Function<Integer, Integer> {
        public Integer apply(Integer x) {
            return x * 2;
        }
    }
    public static final class Always implements Supplier<Integer> {
        public Integer get() {
            return 42;
        }
    }
    public static final class Even implements Predicate<Integer> {
        public boolean test(Integer x) {
            return x % 2 == 0;
        }
    }
    public static final class Desc implements Comparator<Integer> {
        public int compare(Integer a, Integer b) {
            return b.compareTo(a);
        }
    }
    public static final class Plus implements BiFunction<Integer, Integer, Integer> {
        public Integer apply(Integer a, Integer b) {
            return a + b;
        }
    }
    public static final class Times implements IntBinaryOperator {
        public int applyAsInt(int a, int b) {
            return a * b;
        }
    }
    public static final class Range implements Iterable<Integer> {
        public final int from;
        public final int to;
        public Range(int from, int to) {
            this.from = from;
            this.to = to;
        }
        public Iterator<Integer> iterator() {
            return new RangeIterator(from, to);
        }
    }
    public static final class RangeIterator implements Iterator<Integer> {
        public final int start;
        public final int end;
        public int current;
        public RangeIterator(int start, int end) {
            this.start = start;
            this.end = end;
            this.current = start;
        }
        public boolean hasNext() {
            return current < end;
        }
        public Integer next() {
            if (current >= end) {
                throw new NoSuchElementException();
            }
            final var value = current;
            current += 1;
            return value;
        }
    }
    public record Label(String text) implements Supplier<String> {
        public String get() {
            return text;
        }
    }
    public record Offset(int by) implements Function<Integer, Integer> {
        public Integer apply(Integer x) {
            return x + by;
        }
    }
    public static void main(String[] args) {
        System.out.println(new Doubler().apply(4));
        System.out.println(new Always().get());
        System.out.println(new Even().test(4));
        System.out.println(List.of(1, 3, 2).stream().sorted(new Desc()).toList());
        System.out.println(new Plus().apply(2, 3));
        System.out.println(new Times().applyAsInt(2, 3));
        var sum = 0;
        for (final var i : new Range(1, 5)) {
            sum += i;
        }
        System.out.println(sum);
        System.out.println(new Label("x").get());
        System.out.println(new Offset(3).apply(4));
        System.out.println(List.of(1, 2, 3).stream().map(Nulls.nonNull(n -> new Doubler().apply(n))).toList());
    }
}
