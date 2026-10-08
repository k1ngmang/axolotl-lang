package app;
import axl.lang.*;
import org.jspecify.annotations.*;
import java.util.List;

@NullMarked public final class Options {
    public int columnWidth = 80;
    public final List<String> tags = List.of("a", "b");
    public String separator(boolean wide) {
        return (wide ? " | " : "|");
    }
}
