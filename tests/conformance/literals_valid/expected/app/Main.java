package app;
import axl.lang.*;
import org.jspecify.annotations.*;

@NullMarked public final class Main {
    private Main() {}
    public static void main(String[] args) {
        System.out.println(-2147483648);
        System.out.println(0xFFFFFFFF);
        System.out.println(-9223372036854775808L);
        System.out.println('A');
        System.out.println("\\ \t \101 \\".length());
        System.out.println(1_000_000 + 0x7f_ff);
    }
}
