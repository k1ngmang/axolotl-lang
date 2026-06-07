package app;
import axl.lang.*;
import org.jspecify.annotations.*;

@NullMarked public final class Main {
    private Main() {}
    public static int next(int record) {
        final var permits = record + 1;
        return permits;
    }
    public static void main(String[] args) {
        System.out.println(next(1));
    }
}
