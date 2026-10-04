package app;
import axl.lang.*;
import org.jspecify.annotations.*;

@NullMarked public final class Texts {
    private Texts() {}
    public static void print(String text) {
        System.out.println("[" + text + "]");
    }
}
