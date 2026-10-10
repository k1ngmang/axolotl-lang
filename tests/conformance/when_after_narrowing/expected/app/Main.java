package app;
import axl.lang.*;
import org.jspecify.annotations.*;

@NullMarked public final class Main {
    private Main() {}
    public sealed interface Shape {}
    public record Circle(int r) implements Shape {}
    public record Sq(int side) implements Shape {}
    public static int size(Shape s) {
        Nulls.param(s, "s");
        if (!(s instanceof Sq s$1)) {
            return 0;
        }
        switch (s) {
            case Circle s$2 -> {
                return 1;
            }
            case Sq s$3 -> {
                return s$3.side();
            }
        }
    }
    public static void main(String[] args) {
        System.out.println(size(new Sq(5)));
        System.out.println(size(new Circle(1)));
    }
}
