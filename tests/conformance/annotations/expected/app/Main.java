package app;
import axl.lang.*;
import org.jspecify.annotations.*;
import java.util.List;

@NullMarked public final class Main {
    private Main() {}
    @Marker("record") public record Point(
            @Marker("x") int x, int y) {}
    @Marker(value = "class", weight = 2, type = Integer.class, tags = {"a", "b"}) public static final class Counter {
        public final int start;
        @Marker("prop") public int count;
        public Counter(int start) {
            this.start = start;
            this.count = start;
        }
        @Marker(weight = -1) public int next(
                @Marker("p") int step) {
            count += step;
            return count;
        }
        @Override public String toString() {
            return "Counter " + count;
        }
    }
    @Marker("enum") public enum Color { RED, GREEN }
    @Deprecated public static int old() {
        return 1;
    }
    @Marker("value") public static final int limit =
            3;
    public static java.lang.reflect.Method method(Class<?> c, String name) {
        return java.util.Objects.requireNonNull((Nulls.first(Nulls.stream(List.of(c.getDeclaredMethods())).filter(it -> it.getName().equals(name)).iterator())));
    }
    public static java.lang.reflect.Field field(Class<?> c, String name) {
        return java.util.Objects.requireNonNull((Nulls.first(Nulls.stream(List.of(c.getDeclaredFields())).filter(it -> it.getName().equals(name)).iterator())));
    }
    @Marker("fn") public static void main(String[] args) {
        System.out.println(method(Main.class, "main").getAnnotation(Marker.class).value());
        final var counter = Counter.class.getAnnotation(Marker.class);
        System.out.println(counter.value() + " " + counter.weight() + " " + counter.type().getSimpleName() + " " + List.of(counter.tags()));
        final var next = method(Counter.class, "next");
        System.out.println(next.getAnnotation(Marker.class).weight() + " " + List.of(next.getParameters()).get(0).getAnnotation(Marker.class).value());
        System.out.println(field(Counter.class, "count").getAnnotation(Marker.class).value());
        System.out.println(field(Main.class, "limit").getAnnotation(Marker.class).value());
        System.out.println(Point.class.getAnnotation(Marker.class).value() + " " + List.of(Point.class.getRecordComponents()).get(0).getAnnotation(Marker.class).value());
        System.out.println(Color.class.getAnnotation(Marker.class).value());
        System.out.println(method(Main.class, "old").isAnnotationPresent(Deprecated.class));
        System.out.println(new Counter(1).next(2) + " " + new Counter(3) + " " + old());
    }
}
