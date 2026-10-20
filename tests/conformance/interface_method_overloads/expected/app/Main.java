package app;
import axl.lang.*;
import org.jspecify.annotations.*;
import java.util.List;

@NullMarked public final class Main {
    private Main() {}
    public interface Expr {
        boolean interpret(int n);
    }
    public static final class Positive implements Expr {
        public boolean interpret(String text) {
            Nulls.param(text, "text");
            return interpret(Integer.parseInt(text));
        }
        @Override public boolean interpret(int n) {
            return n > 0;
        }
    }
    public static final class Negative implements Expr {
        @Override public boolean interpret(int n) {
            return n < 0;
        }
        public boolean interpret(String text) {
            Nulls.param(text, "text");
            return interpret(Integer.parseInt(text));
        }
    }
    public record Above(int limit) implements Expr {
        @Override public boolean interpret(int n) {
            return n > limit;
        }
        public boolean interpret(String text) {
            Nulls.param(text, "text");
            return interpret(Integer.parseInt(text));
        }
    }
    public interface Store<T extends @Nullable Object> {
        String put(T value);
    }
    public static final class Ints implements Store<Integer> {
        public String put(long value) {
            return "long " + value;
        }
        @Override public String put(Integer value) {
            Nulls.param(value, "value");
            return "int " + value;
        }
    }
    public interface Named {
        boolean interpret(String name);
    }
    public static final class Both implements Expr, Named {
        @Override public boolean interpret(String name) {
            Nulls.param(name, "name");
            return name.isEmpty();
        }
        @Override public boolean interpret(int n) {
            return n == 0;
        }
    }
    public static void main(String[] args) {
        final List<Expr> exprs = List.<Expr>of(new Positive(), new Negative(), new Above(3));
        for (final var e : exprs) {
            System.out.println("" + e.interpret(5) + " " + e.interpret(-5));
        }
        System.out.println("" + new Positive().interpret("7") + " " + new Negative().interpret("7") + " " + new Above(3).interpret("2"));
        final Store<Integer> store = new Ints();
        System.out.println(store.put(1) + ", " + new Ints().put(2L));
        final Named named = new Both();
        final Expr expr = new Both();
        System.out.println("" + named.interpret("") + " " + expr.interpret(1));
    }
}
