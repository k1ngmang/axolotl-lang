package app;
import axl.lang.*;
import org.jspecify.annotations.*;
import java.util.ArrayList;
import java.util.List;

@NullMarked public final class Main {
    private Main() {}
    public sealed interface Shape {}
    public record Circle(int r) implements Shape {}
    public record Dot(String tag) implements Shape {
        public Dot {
            Nulls.param(tag, "tag");
        }
    }
    public static void main(String[] args) {
        loop$1: for (final var x : List.of(1, 2, 3, 4)) {
            switch (Nulls.box(x)) {
                case 3 -> {
                    break loop$1;
                }
                case null, default -> {
                    System.out.println("saw " + x);
                }
            }
            System.out.println("after when " + x);
        }
        var i = 0;
        loop$2: while (i < 10) {
            i += 1;
            switch (i) {
                case 2 -> {
                    continue;
                }
                case 4 -> {
                    break loop$2;
                }
                default -> {
                    System.out.print(i + " ");
                }
            }
            System.out.print("| ");
        }
        System.out.println("i=" + i);
        System.out.println("sum=" + sumUntilDot(List.<Shape>of(new Circle(1), new Dot("stop"), new Circle(2))));
        System.out.println(firstOrNone(List.of(1, 2)) + " " + firstOrNone(List.of(2, 1)));
        nested();
    }
    public static int sumUntilDot(List<Shape> shapes) {
        Nulls.param(shapes, "shapes");
        var sum = 0;
        loop$1: for (final var s : shapes) {
            switch (s) {
                case Circle s$1 -> {
                    sum += s$1.r();
                }
                case Dot s$2 -> {
                    break loop$1;
                }
            }
        }
        return sum;
    }
    public static int firstOrNone(List<Integer> xs) {
        Nulls.param(xs, "xs");
        loop$1: for (final var x : xs) {
            switch (Nulls.box(x)) {
                case 1 -> {
                    break loop$1;
                }
                case null, default -> {
                    return x;
                }
            }
        }
        return -1;
    }
    public static void nested() {
        final var seen = new ArrayList<Integer>();
        for (final var row : List.of(List.of(1, 2, 3), List.of(4, 5, 6))) {
            loop$1: for (final var x : row) {
                if (x > 0) {
                    switch (Nulls.box(x)) {
                        case 2, 5 -> {
                            break loop$1;
                        }
                        case null, default -> {
                            seen.add(x);
                        }
                    }
                }
            }
            if (row.size() > 5) {
                break;
            }
        }
        List.of(10, 20).stream().forEach(it -> {
            loop$2: for (final var y : List.of(it, it + 1)) {
                switch (Nulls.box(y)) {
                    case 20 -> {
                        break loop$2;
                    }
                    case null, default -> {
                        seen.add(y);
                    }
                }
            }
        });
        System.out.println(seen);
    }
}
