package app;
import axl.lang.*;
import org.jspecify.annotations.*;

@NullMarked public enum Color { RED, GREEN, BLUE;
    public static boolean warm(Color c) {
        Nulls.param(c, "c");
        return c == Color.RED;
    }
}
