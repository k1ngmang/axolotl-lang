package app;
import axl.lang.*;
import org.jspecify.annotations.*;

@NullMarked public final class Main {
    private Main() {}
    public static void main(String[] args) {
        final Runnable r = () -> {
        };
        final Runnable s = () -> {
        };
        r.run();
        s.run();
        System.out.println("done");
    }
}
