package app;
import axl.lang.*;
import org.jspecify.annotations.*;
import java.util.List;

@NullMarked public final class Main {
    private Main() {}
    public sealed interface Shape {
        String name();
    }
    public record Circle(String name, double radius) implements Shape {}
    public record Square(String name, double side) implements Shape {}
    public record Member(String name, @Nullable String email) {}
    public static List<Circle> circles(List<Shape> shapes) {
        return shapes.stream().filter(it -> it instanceof Circle it$1).<Circle>map(Circle.class::cast).toList();
    }
    public static List<Double> radii(List<Shape> shapes) {
        return shapes.stream().filter(it -> it instanceof Circle it$1).<Circle>map(Circle.class::cast).map(Nulls.nonNull(it -> it.radius())).toList();
    }
    public static List<String> emails(List<Member> members) {
        return Nulls.nonNulls(members.stream().map(Nulls.nullable(it -> it.email())).filter(it -> it != null)).toList();
    }
    public static void main(String[] args) {
        final List<Shape> shapes = List.<Shape>of(new Circle("a", 1.5), new Square("b", 2.0), new Circle("c", 2.5));
        System.out.println(circles(shapes).size());
        System.out.println(radii(shapes));
        final var members = List.of(new Member("x", "x@e.org"), new Member("y", null), new Member("z", "z@e.org"));
        System.out.println(emails(members));
    }
}
