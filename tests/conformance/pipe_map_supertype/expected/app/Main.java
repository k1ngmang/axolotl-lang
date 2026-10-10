package app;
import axl.lang.*;
import org.jspecify.annotations.*;
import java.util.List;

@NullMarked public final class Main {
    private Main() {}
    public sealed interface Shape {}
    public record Dot(int x) implements Shape {}
    public record Box(int w) implements Shape {}
    public record Group(List<Shape> shapes) {
        public Group {
            Nulls.param(shapes, "shapes");
        }
    }
    public static List<Shape> dots(List<Integer> xs) {
        Nulls.param(xs, "xs");
        return xs.stream().<Shape>map(Nulls.nonNull(it -> new Dot(it))).toList();
    }
    public static List<Shape> boxes(List<Integer> xs) {
        Nulls.param(xs, "xs");
        return xs.stream().<Shape>map(Nulls.nonNull(it -> new Box(it))).distinct().limit(2).toList();
    }
    public static void main(String[] args) {
        final List<Shape> typed = List.of(1, 2).stream().<Shape>map(Nulls.nonNull(it -> new Dot(it))).toList();
        final List<Dot> positive = List.of(-1, 3).stream().map(Nulls.nonNull(it -> new Dot(it))).filter(it -> it.x() > 0).toList();
        final List<Shape> boxed = List.of(4).stream().<Shape>map(Nulls.nonNull(it -> new Box(it))).toList();
        final var group = new Group(boxed);
        System.out.println(typed);
        System.out.println(positive);
        System.out.println(dots(List.of(5)));
        System.out.println(boxes(List.of(6, 6, 7, 8)));
        System.out.println(group.shapes().size());
    }
}
