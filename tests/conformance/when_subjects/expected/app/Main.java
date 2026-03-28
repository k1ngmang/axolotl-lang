package app;
import axl.lang.*;
import org.jspecify.annotations.*;

@NullMarked public final class Main {
    private Main() {}
    public enum Color { RED, GREEN, BLUE }
    public sealed interface Shape {}
    public record Circle(double radius) implements Shape {}
    public record Square(double side) implements Shape {}
    public static String big(long n) {
        final var $e1 = n;
        switch ($e1 == 1L || $e1 == 2L ? 0 : $e1 == 1000000000000L ? 1 : -1) {
            case 0 -> {
                return "small";
            }
            case 1 -> {
                return "huge";
            }
            default -> {
                return "other";
            }
        }
    }
    public static String half(double d) {
        final var $e1 = d;
        switch ($e1 == 0.5 ? 0 : -1) {
            case 0 -> {
                return "half";
            }
            default -> {
                return "not half";
            }
        }
    }
    public static String yes(boolean b) {
        final var $e1 = b;
        switch ($e1 == true ? 0 : -1) {
            case 0 -> {
                return "yes";
            }
            default -> {
                return "no";
            }
        }
    }
    public static String sign(int n) {
        switch (n) {
            case -1 -> {
                return "minus";
            }
            case 0 -> {
                return "zero";
            }
            default -> {
                return "plus";
            }
        }
    }
    public static boolean warm(Color c) {
        switch (c) {
            case Color.RED -> {
                return true;
            }
            case GREEN, BLUE -> {
                return false;
            }
            default -> throw new IllegalStateException();
        }
    }
    public static String size(@Nullable Integer n) {
        switch (n) {
            case 0 -> {
                return "none";
            }
            case null, default -> {
                return "some";
            }
        }
    }
    public static int length(@Nullable String s) {
        switch (s) {
            case String s$1 -> {
                return s$1.length();
            }
            case null -> {
                return -1;
            }
        }
    }
    public static double area(@Nullable Shape shape) {
        switch (shape) {
            case Circle shape$1 -> {
                return shape$1.radius();
            }
            case Square shape$2 -> {
                return shape$2.side() * shape$2.side();
            }
            case null -> {
                return 0.0;
            }
        }
    }
    public static void main(String[] args) {
        System.out.println(big(2L) + " " + big(1000000000000L) + " " + big(7L));
        System.out.println(half(0.5) + " " + half(1.0));
        System.out.println(yes(true) + " " + yes(false));
        System.out.println(sign(-1) + " " + sign(0) + " " + sign(5));
        System.out.println("" + warm(Color.RED) + " " + warm(Color.BLUE));
        System.out.println(size(null) + " " + size(0));
        System.out.println("" + length(null) + " " + length("abc"));
        System.out.println("" + area(null) + " " + area(new Square(2.0)));
    }
}
