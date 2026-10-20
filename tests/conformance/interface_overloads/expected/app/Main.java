package app;
import axl.lang.*;
import org.jspecify.annotations.*;
import java.util.List;

@NullMarked public final class Main {
    private Main() {}
    public interface Expr {
        boolean interpret(int n);
        default boolean interpret(String text) {
            Nulls.param(text, "text");
            return interpret(Integer.parseInt(text));
        }
    }
    public static final class Positive implements Expr {
        @Override public boolean interpret(int n) {
            return n > 0;
        }
    }
    public interface Sink<T extends @Nullable Object> {
        String add(T value);
        default String add(List<T> values) {
            Nulls.param(values, "values");
            return String.join(",", values.stream().map(Nulls.nonNull(v -> add(v))).toList());
        }
    }
    public static final class Printer implements Sink<String> {
        @Override public String add(String value) {
            Nulls.param(value, "value");
            return "<" + value + ">";
        }
    }
    public static void main(String[] args) {
        final Expr e = new Positive();
        System.out.println("" + e.interpret(3) + " " + e.interpret("-3"));
        System.out.println(new Printer().add(List.of("a", "b")));
    }
}
