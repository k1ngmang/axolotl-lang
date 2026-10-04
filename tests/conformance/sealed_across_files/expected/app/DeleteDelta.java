package app;
import axl.lang.*;
import org.jspecify.annotations.*;
import java.util.List;

@NullMarked public final class DeleteDelta implements Delta {
    public final List<String> lines;
    public DeleteDelta(List<String> lines) {
        this.lines = lines;
    }
    public String describe() {
        return "delete " + lines.size();
    }
    @Override public List<String> lines() { return lines; }
}
