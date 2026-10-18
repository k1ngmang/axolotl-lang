package app;
import axl.lang.*;
import org.jspecify.annotations.*;
import java.util.ArrayList;
import java.util.List;

@NullMarked public final class Main {
    private Main() {}
    public static final class Collector implements Handler<List<String>> {
        public final ArrayList<String> items = new ArrayList<String>();
        @Override public void value(String text) {
            Nulls.param(text, "text");
            items.add(text);
        }
    }
    public interface Named {
        String name();
        List<String> tags();
        default String greeting() {
            return "hello, " + name() + describe();
        }
        default String describe() {
            return (tags().isEmpty() ? "" : " " + tags());
        }
        default String shout() {
            return loud(this.greeting());
        }
        private String loud(String text) {
            return text.toUpperCase();
        }
    }
    public record Person(String name, List<String> tags) implements Named {
        public Person {
            Nulls.param(name, "name");
            Nulls.param(tags, "tags");
        }
    }
    public static final class Robot implements Named {
        public final String name;
        public final List<String> tags;
        public Robot(String name) {
            Nulls.param(name, "name");
            this.name = name;
            this.tags = List.of("metal");
        }
        @Override public String describe() {
            return " beep" + Named.super.describe();
        }
        @Override public String name() { return name; }
        @Override public List<String> tags() { return tags; }
    }
    public interface Coded {
        int code();
        String label();
    }
    public interface Labeled extends Coded {
        @Override default String label() {
            return "#" + code();
        }
    }
    public enum Planet implements Labeled {
        EARTH(3), MARS(4);
        public final int code;
        Planet(int code) {
            this.code = code;
        }
        @Override public int code() { return code; }
    }
    public sealed interface Shape {
        double area();
        default boolean big() {
            return area() > 10.0;
        }
    }
    public record Square(double side) implements Shape {
        @Override public double area() {
            return side * side;
        }
    }
    public record Dot(int x) implements Shape {
        @Override public double area() {
            return 0.0;
        }
    }
    public static String kind(Shape shape) {
        Nulls.param(shape, "shape");
        switch (shape) {
            case Square shape$1 -> {
                return "square " + shape$1.big();
            }
            case Dot shape$2 -> {
                return "dot " + shape$2.big();
            }
        }
    }
    public static void main(String[] args) {
        final var collector = new Collector();
        Handler.parse(List.of("a", "b"), collector);
        collector.endArray(collector.startArray());
        System.out.println(collector.items);
        Handler.parse(List.of("c"), JavaUse.silent());
        System.out.println(new Person("ann", List.of()).greeting());
        System.out.println(new Person("bob", List.of("x")).shout());
        System.out.println(new Robot("r2").greeting());
        System.out.println(Planet.EARTH.label() + " " + Planet.MARS.label());
        System.out.println(kind(new Square(4.0)) + ", " + kind(new Dot(1)));
        System.out.println(JavaUse.run());
    }
}
