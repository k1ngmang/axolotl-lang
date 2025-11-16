package app;
import axl.lang.*;
import org.jspecify.annotations.*;

@NullMarked public final class Main {
    private Main() {}
    public static void main(String[] args) {
        System.out.println("q\"" + "x");
        System.out.println('\'');
        System.out.println("\u00e9\\u0041");
        System.out.println(0x10 + 0);
    }
}
