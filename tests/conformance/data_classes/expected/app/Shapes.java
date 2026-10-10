package app;
import axl.lang.*;
import org.jspecify.annotations.*;

@NullMarked public final class Shapes {
    private Shapes() {}
    public sealed interface Shape {
        String name();
    }
    public sealed interface Round extends Shape {}
    public record Circle(String name, double radius) implements Round {
        public Circle {
            Nulls.param(name, "name");
        }
    }
    public record Square(String name, double side, @Nullable String label) implements Shape {
        public Square {
            Nulls.param(name, "name");
        }
    }
    @SuppressWarnings("EffectivelyPrivate") private record Hidden(int id) {}
    public static double area(Shape shape) {
        Nulls.param(shape, "shape");
        return 0.0;
    }
    public static String describe(@Nullable String label) {
        return Nulls.<String>or(label, () -> "none");
    }
}
