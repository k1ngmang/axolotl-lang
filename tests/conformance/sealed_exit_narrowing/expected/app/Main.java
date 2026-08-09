package app;
import axl.lang.*;
import org.jspecify.annotations.*;

@NullMarked public final class Main {
    private Main() {}
    public sealed interface Option<T extends @Nullable Object> {}
    public record Some<T extends @Nullable Object>(T value) implements Option<T> {}
    public record None<T extends @Nullable Object>() implements Option<T> {}
    public sealed interface Shape {}
    public record Circle(double radius) implements Shape {}
    public record Dot() implements Shape {}
    public static String describe(Option<Integer> o) {
        if (o instanceof None<Integer> o$1) {
            return "none";
        }
        return "some " + ((Some<Integer>) o).value();
    }
    public static String label(Option<String> o) {
        return (o instanceof None<String> o$1 ? "none" : ((Some<String>) o).value());
    }
    public static double radius(Shape s) {
        if (s instanceof Dot s$1) {
            return 0.0;
        }
        return ((Circle) s).radius();
    }
    public static void main(String[] args) {
        System.out.println(describe(new Some<Integer>(3)));
        System.out.println(describe(new None<Integer>()));
        System.out.println(label(new Some<String>("x")));
        System.out.println(radius(new Circle(2.5)));
        System.out.println(radius(new Dot()));
    }
}
