package app;
import axl.lang.*;
import org.jspecify.annotations.*;
import static java.util.Objects.requireNonNull;

@NullMarked public final class Imported {
    private Imported() {}
    public static String shout(String text) {
        requireNonNull(text, "text is null");
        return text.toUpperCase();
    }
}
