package app;
import axl.lang.*;
import org.jspecify.annotations.*;
import app.Shape.Private$.Square;

@NullMarked public sealed interface Shape {
    double area();
    public record Circle(double r) implements Shape {
        @Override public double area() {
            return 3.0 * r * r;
        }
    }
    public static Shape unit() {
        return new Square(1.0);
    }
    public static String name(Shape shape) {
        Nulls.param(shape, "shape");
        switch (shape) {
            case Square shape$1 -> {
                return "square";
            }
            case Circle shape$2 -> {
                return "circle";
            }
        }
    }
    final class Private$ {
        private Private$() {}
        record Square(double side) implements Shape {
            @Override public double area() {
                return side * side;
            }
        }
    }
}
