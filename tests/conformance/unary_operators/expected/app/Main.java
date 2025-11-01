package app;
import axl.lang.*;
import org.jspecify.annotations.*;

@NullMarked public final class Main {
    private Main() {}
    public static void main(String[] args) {
        var x = 5;
        System.out.println(- -x);
        System.out.println(-(-x));
        System.out.println(+ +x);
        System.out.println(- +x);
        System.out.println(!!(x > 1));
        System.out.println(x);
    }
}
