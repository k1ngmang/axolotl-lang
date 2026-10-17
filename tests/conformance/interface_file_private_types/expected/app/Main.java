package app;
import axl.lang.*;
import org.jspecify.annotations.*;
import java.io.Writer;

@NullMarked public final class Main {
    private Main() {}
    public static void main(String[] args) {
        System.out.println(Config.MINIMAL.create(Writer.nullWriter()));
        System.out.println(Config.describe(Config.MINIMAL));
        System.out.println(Shape.name(Shape.unit()) + " " + Shape.unit().area());
    }
}
