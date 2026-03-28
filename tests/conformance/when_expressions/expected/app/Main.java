package app;
import axl.lang.*;
import org.jspecify.annotations.*;

@NullMarked public final class Main {
    private Main() {}
    public sealed interface Shape {}
    public record Circle(double radius) implements Shape {}
    public record Rect(double width, double height) implements Shape {}
    public record Empty(@Nullable String label) implements Shape {}
    public static double area(Shape shape) {
        switch (shape) {
            case Circle shape$1 -> {
                return 3.0 * shape$1.radius() * shape$1.radius();
            }
            case Rect shape$2 -> {
                return shape$2.width() * shape$2.height();
            }
            case Empty shape$3 -> {
                return 0.0;
            }
        }
    }
    public static String name(Shape shape) {
        switch (shape) {
            case Circle shape$1 -> {
                return "circle";
            }
            case Rect shape$2 -> {
                final var big = shape$2.width() > 10.0;
                return "rect " + big;
            }
            case Empty shape$3 -> {
                return Nulls.or(shape$3.label(), () -> "nothing");
            }
        }
    }
    public static String size(int n) {
        switch (n) {
            case 0 -> {
                return "none";
            }
            case 1, 2 -> {
                return "few";
            }
            default -> {
                return "many";
            }
        }
    }
    public static int kind(String word) {
        final var length = switch (word) {
            case "a" -> 1;
            case "bb" -> 2;
            default -> {
                yield word.length() * 10;
            }
        };
        return length + 1;
    }
    public static void show(Shape shape) {
        switch (shape) {
            case Circle shape$1 -> {
                System.out.println("circle " + shape$1.radius());
            }
            case Rect shape$2 -> {
                System.out.println("rect");
                System.out.println(shape$2.width());
            }
            case Empty shape$3 -> {
                System.out.println("empty");
            }
        }
    }
    public static Shape pick() {
        return new Rect(2.0, 3.0);
    }
    public static String bound() {
        final var e$e1 = pick();
        switch (e$e1) {
            case Circle e$2 -> {
                return "c";
            }
            case Rect e$3 -> {
                return "r " + e$3.width();
            }
            default -> {
                return "other";
            }
        }
    }
    public static void main(String[] args) {
        System.out.println(area(new Circle(2.0)));
        System.out.println(area(new Rect(2.0, 3.0)));
        System.out.println(name(new Rect(20.0, 1.0)));
        System.out.println(name(new Empty(null)));
        System.out.println(size(0) + size(2) + size(9));
        System.out.println(kind("a") + kind("zzz"));
        show(new Circle(1.0));
        show(new Rect(1.0, 2.0));
        show(new Empty(null));
        System.out.println(bound());
    }
}
