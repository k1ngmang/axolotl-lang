package app;
import axl.lang.*;
import org.jspecify.annotations.*;
import app.Shapes.Circle;
import app.Shapes.Square;
import app.Shapes.Shape;

@NullMarked public final class Main {
    private Main() {}
    public static void main(String[] args) {
        final var circle = new Circle("c", 2.0);
        final var square = new Square("s", 1.5, null);
        final java.util.List<Shape> shapes = java.util.List.of(circle, square);
        System.out.println(circle);
        System.out.println(square.label());
        System.out.println(circle.equals(new Circle("c", 2.0)));
        for (final var shape : shapes) {
            System.out.println(shape.name());
        }
    }
}
