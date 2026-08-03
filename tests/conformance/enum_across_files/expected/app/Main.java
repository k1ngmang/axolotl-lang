package app;
import axl.lang.*;
import org.jspecify.annotations.*;
import app.Model.Level;
import app.Model.Shape;
import app.Model.Circle;
import app.Model.Square;

@NullMarked public final class Main {
    private Main() {}
    public static String label(Level level) {
        switch (level) {
            case Level.LOW -> {
                return "low";
            }
            case Level.MID -> {
                return "mid";
            }
            case Level.HIGH -> {
                return "high";
            }
            default -> throw new IllegalStateException();
        }
    }
    public static double area(Shape s) {
        switch (s) {
            case Circle s$1 -> {
                return 3.0 * s$1.radius() * s$1.radius();
            }
            case Square s$2 -> {
                return s$2.side() * s$2.side();
            }
        }
    }
    public static void main(String[] args) {
        System.out.println(label(Level.MID));
        System.out.println(area(new Square(2.0)));
    }
}
