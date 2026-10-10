package app;
import axl.lang.*;
import org.jspecify.annotations.*;
import java.time.DayOfWeek;
import lib.Shapes.Circle;
import lib.Shapes.Dot;
import lib.Shapes.Level;
import lib.Shapes.Shape;
import lib.Shapes.Square;

@NullMarked public final class Main {
    private Main() {}
    public static int area(Shape s) {
        Nulls.param(s, "s");
        switch (s) {
            case Circle s$1 -> {
                return 3 * s$1.r() * s$1.r();
            }
            case Square s$2 -> {
                return s$2.side() * s$2.side();
            }
            case Dot s$3 -> {
                return 0;
            }
        }
    }
    public static int weight(Level l) {
        Nulls.param(l, "l");
        switch (l) {
            case Level.LOW -> {
                return 1;
            }
            case Level.MID -> {
                return 2;
            }
            case Level.HIGH -> {
                return 3;
            }
            default -> throw new IllegalStateException();
        }
    }
    public static boolean weekend(DayOfWeek d) {
        Nulls.param(d, "d");
        switch (d) {
            case DayOfWeek.SATURDAY, DayOfWeek.SUNDAY -> {
                return true;
            }
            case DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY -> {
                return false;
            }
            default -> throw new IllegalStateException();
        }
    }
    public static void main(String[] args) {
        System.out.println(area(new Circle(2)) + area(new Square(3)) + area(new Dot(1)));
        System.out.println(weight(Level.MID));
        System.out.println(weekend(DayOfWeek.SUNDAY));
    }
}
