package app;
import axl.lang.*;
import org.jspecify.annotations.*;
import static util.Parse.doubled;

@NullMarked public final class Main {
    private Main() {}
    public static String greet(@Nullable String name) {
        return "Hello, " + Nulls.<String>or(Nulls.map(name, $n1 -> $n1.trim()), () -> "stranger");
    }
    public static void main(String[] args) {
        System.out.println(greet(null));
        System.out.println(doubled("21"));
    }
}
