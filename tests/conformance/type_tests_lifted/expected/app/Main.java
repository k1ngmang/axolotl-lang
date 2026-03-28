package app;
import axl.lang.*;
import org.jspecify.annotations.*;

@NullMarked public final class Main {
    private Main() {}
    public sealed interface Shape {}
    public record Circle(double radius) implements Shape {}
    public record Square(double side) implements Shape {}
    public static Result<Boolean, String> check(double r) {
        return (r >= 0.0 ? new Ok<>(r > 1.0) : new Err<>("negative"));
    }
    public static String kind(Object o) {
        switch (o) {
            case Integer o$1 -> {
                return "int " + o$1;
            }
            case String o$2 -> {
                return "string " + o$2;
            }
            default -> {
                return "other";
            }
        }
    }
    public static int next(Result<Integer, String> r) {
        switch (r) {
            case Ok<Integer, String> r$1 -> {
                return r$1.value() + 1;
            }
            case Err<Integer, String> r$2 -> {
                return r$2.error().length();
            }
        }
    }
    public static Result<String, String> big(Shape shape) {
        if (shape instanceof Circle shape$1) {
            final var $t1 = check(shape$1.radius());
            if ($t1.isErr()) return Result.propagate($t1);
            if (Result.unwrap($t1)) {
                return new Ok<>("big circle " + shape$1.radius());
            }
        }
        return new Ok<>("other");
    }
    public static Result<String, String> radius(Shape shape) {
        if (!(shape instanceof Circle shape$1)) {
            return new Ok<>("not round");
        }
        final var $t1 = check(-1.0);
        if ($t1.isErr()) return Result.propagate($t1);
        if (Result.unwrap($t1)) {
            return new Ok<>("not round");
        }
        return new Ok<>("r=" + shape$1.radius());
    }
    public static void main(String[] args) {
        System.out.println(kind(1) + ", " + kind("a"));
        System.out.println(next(new Ok<>(5)));
        System.out.println(big(new Circle(2.0)));
        System.out.println(big(new Square(1.0)));
        System.out.println(radius(new Square(1.0)));
        System.out.println(radius(new Circle(1.0)));
    }
}
