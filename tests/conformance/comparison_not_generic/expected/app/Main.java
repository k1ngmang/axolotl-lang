package app;
import axl.lang.*;
import org.jspecify.annotations.*;

@NullMarked public final class Main {
    private Main() {}
    public static final int LOW = 1;
    public static boolean both(boolean a, boolean b) {
        return a && b;
    }
    public static void main(String[] args) {
        final var v = 5;
        System.out.println(both(LOW < v, v > (2 - 1)));
    }
}
