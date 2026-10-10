package app;
import axl.lang.*;
import org.jspecify.annotations.*;
import java.util.List;
import java.util.Set;

@NullMarked public final class Main {
    private Main() {}
    public sealed interface Shape {}
    public non-sealed interface Named extends Shape {}
    public record Dot(int x) implements Named {}
    public record Box(int w) implements Shape {}
    public static final class Circle implements Named {
        public final int r;
        public Circle(int r) {
            this.r = r;
        }
    }
    public static int count(List<Shape> shapes) {
        Nulls.param(shapes, "shapes");
        return shapes.size();
    }
    public static int names(Set<Named> named) {
        Nulls.param(named, "named");
        return named.size();
    }
    public static void main(String[] args) {
        final var shapes = List.<Shape>of(new Dot(1), new Box(2));
        System.out.println(count(shapes));
        System.out.println(count(List.<Shape>of(new Dot(1), new Box(2), new Circle(3))));
        System.out.println(names(Set.<Named>of(new Dot(4), new Circle(5))));
        final var same = List.of(new Dot(6), new Dot(7));
        System.out.println(same.get(1).x());
    }
}
