package app;
import axl.lang.*;
import org.jspecify.annotations.*;
import java.util.List;

@NullMarked public non-sealed interface Conflict extends Delta {
    String reason();
    public record Clash(List<String> lines, String reason) implements Conflict {
        public Clash {
            Nulls.param(lines, "lines");
            Nulls.param(reason, "reason");
        }
    }
}
