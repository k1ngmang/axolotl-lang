package app;
import axl.lang.*;
import org.jspecify.annotations.*;
import java.util.List;

@NullMarked public record InsertDelta(List<String> lines) implements Delta {
    public InsertDelta {
        Nulls.param(lines, "lines");
    }
}
