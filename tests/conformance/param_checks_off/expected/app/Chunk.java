package app;
import axl.lang.*;
import org.jspecify.annotations.*;
import java.util.List;

@NullMarked public record Chunk(int position, List<String> lines) {
    public static final class Patch {
        public final String name;
        public Patch(String name) {
            this.name = name;
        }
        public Patch(String name, String suffix) {
            this(name + suffix);
        }
        public String describe(String prefix) {
            return prefix + name;
        }
    }
    public static int last(Chunk c) {
        return c.position + c.lines.size() - 1;
    }
}
