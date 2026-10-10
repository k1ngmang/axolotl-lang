package app;
import axl.lang.*;
import org.jspecify.annotations.*;

@NullMarked public final class Main {
    private Main() {}
    public sealed interface Shape {}
    public record Circle(double radius) implements Shape {}
    public record Square(double side) implements Shape {}
    public static String sign(int n) {
        return (n < 0 ? "negative" : (n == 0 ? "zero" : "positive"));
    }
    public static String describe(Shape shape) {
        Nulls.param(shape, "shape");
        return (shape instanceof Circle shape$1 ? "circle " + shape$1.radius() : "other");
    }
    public static double area(Shape shape) {
        Nulls.param(shape, "shape");
        switch (shape) {
            case Circle shape$1 -> {
                return 3.0 * shape$1.radius() * shape$1.radius();
            }
            case Square shape$2 -> {
                return shape$2.side() * shape$2.side();
            }
        }
    }
    public static double checked(@Nullable Shape shape) {
        if (shape == null) {
            throw new IllegalArgumentException("no shape");
        }
        return area(shape);
    }
    public static String kind(int n) {
        switch (n) {
            case 1 -> {
                return "one";
            }
            case 2 -> {
                return "two";
            }
            default -> {
                throw new IllegalStateException("unexpected " + n);
            }
        }
    }
    public static void main(String[] args) {
        System.out.println(sign(-3) + sign(0) + sign(4));
        System.out.println(describe(new Circle(1.0)) + ", " + describe(new Square(2.0)));
        System.out.println(checked(new Square(3.0)));
        System.out.println(kind(2));
        final java.util.function.Supplier<String> failing = () -> {
            throw new IllegalStateException("late");
        };
        try0(failing);
    }
    public static void try0(java.util.function.Supplier<String> supplier) {
        Nulls.param(supplier, "supplier");
        final var result = Result.attempt(() -> supplier.get(), e -> "caught " + e.getMessage());
        System.out.println(result);
    }
}
