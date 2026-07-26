package app;
import axl.lang.*;
import org.jspecify.annotations.*;
import java.util.Comparator;
import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;

@NullMarked public final class Main {
    private Main() {}
    public static final class Seven implements Supplier<Integer> {
        public Integer get() {
            return 7;
        }
    }
    public static final class Inc implements Function<Integer, Integer> {
        public Integer apply(Integer x) {
            return x + 1;
        }
    }
    public static final class Desc implements Comparator<Integer> {
        public int compare(Integer a, Integer b) {
            return b.compareTo(a);
        }
    }
    public static void main(String[] args) {
        System.out.println(new Seven().get());
        System.out.println(new Inc().apply(1));
        System.out.println(List.of(1, 3, 2).stream().sorted(new Desc()).toList());
    }
}
