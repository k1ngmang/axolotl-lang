package app;
import axl.lang.*;
import org.jspecify.annotations.*;

@NullMarked public record Point(int x, int y) {
    public static final Point origin = new Point(0, 0);
    public static Point add(Point a, Point b) {
        return new Point(a.x() + b.x(), a.y() + b.y());
    }
}
