package app;
import axl.lang.*;
import org.jspecify.annotations.*;

@NullMarked public final class Shapes {
    private Shapes() {}
    public sealed interface Shape {
        String name();
    }
    public sealed interface Round extends Shape {}
    public record Circle(String name, double radius) implements Round {}
    public record Square(String name, double side, @Nullable String label) implements Shape {}
    private record Hidden(int id) {}
    public static double area(Shape shape) {
        return 0.0;
    }
    public static String describe(@Nullable String label) {
        return Nulls.or(label, () -> "none");
    }
}
