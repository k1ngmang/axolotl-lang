package app;
import axl.lang.*;
import org.jspecify.annotations.*;
import java.util.List;

@NullMarked public final class Deltas {
    private Deltas() {}
    public record EqualDelta(List<String> lines) implements Delta {
        public EqualDelta {
            Nulls.param(lines, "lines");
        }
    }
    public static Delta equal(List<String> lines) {
        Nulls.param(lines, "lines");
        return new EqualDelta(lines);
    }
}
