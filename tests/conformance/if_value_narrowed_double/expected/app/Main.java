package app;
import axl.lang.*;
import org.jspecify.annotations.*;

@NullMarked public final class Main {
    private Main() {}
    public sealed interface Shape {}
    public record Circle(double radius) implements Shape {}
    public record Square(double side) implements Shape {}
    public record Dot() implements Shape {}
    public static double size(Shape s) {
        return (s instanceof Circle s$1 ? Double.valueOf(s$1.radius()) : Nulls.toDouble((s instanceof Square s$2 ? s$2.side() : 0.0)));
    }
    public static String label(Shape s) {
        return (s instanceof Circle s$1 ? "circle " + s$1.radius() : (s instanceof Square s$2 ? "square " + s$2.side() : "dot"));
    }
    public static void main(String[] args) {
        System.out.println(size(new Circle(1.5)));
        System.out.println(size(new Square(2.0)));
        System.out.println(size(new Dot()));
        System.out.println(label(new Square(3.0)));
    }
}
