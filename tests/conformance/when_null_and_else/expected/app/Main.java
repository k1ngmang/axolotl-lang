package app;
import axl.lang.*;
import org.jspecify.annotations.*;

@NullMarked public final class Main {
    private Main() {}
    public sealed interface Shape {}
    public record Circle(double radius) implements Shape {}
    public record Square(double side) implements Shape {}
    public enum Color { RED, GREEN }
    public static String word(@Nullable String s) {
        switch (s) {
            case "a" -> {
                return "A";
            }
            case null -> {
                return "none";
            }
            default -> {
                return "other";
            }
        }
    }
    public static String shape(@Nullable Shape s) {
        switch (s) {
            case Circle s$1 -> {
                return "circle";
            }
            case null -> {
                return "nothing";
            }
            default -> {
                return "another shape";
            }
        }
    }
    public static String color(@Nullable Color c) {
        switch (c) {
            case Color.RED -> {
                return "red";
            }
            case null -> {
                return "no color";
            }
            default -> {
                return "another color";
            }
        }
    }
    public static void main(String[] args) {
        System.out.println(word("a") + " " + word(null) + " " + word("b"));
        System.out.println(shape(new Circle(1.0)) + " " + shape(null) + " " + shape(new Square(2.0)));
        System.out.println(color(Color.RED) + " " + color(null) + " " + color(Color.GREEN));
    }
}
