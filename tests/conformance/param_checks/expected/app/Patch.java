package app;
import axl.lang.*;
import org.jspecify.annotations.*;
import java.util.List;

@NullMarked public final class Patch {
    public final String name;
    public final List<String> lines;
    public final @Nullable String note;
    public final int size;
    public Patch(String name, List<String> lines, @Nullable String note, int size) {
        Nulls.param(name, "name");
        Nulls.param(lines, "lines");
        this.name = name;
        this.lines = lines;
        this.note = note;
        this.size = size;
    }
    public Patch(String name) {
        this(Nulls.param(name, "name"), List.of(), null, 0);
    }
    public Patch(List<String> lines, String label) {
        this(label.trim(), Nulls.param(lines, "lines"), null, lines.size());
        Nulls.param(label, "label");
    }
    private Patch(int size, String name) {
        this(name, List.of(), null, size);
    }
    public String applyTo(List<String> target, int at, @Nullable String comment) {
        Nulls.param(target, "target");
        return name + " at " + at + " of " + target.size();
    }
    private String hidden(String text) {
        return text;
    }
    public String describe() {
        return hidden(name) + new Hidden(name).show("!");
    }
    public Patch resized(int size) {
        return new Patch(size, name);
    }
    public static final class Failure extends RuntimeException {
        public final String reason;
        public Failure(String reason) {
            super("failed: " + reason);
            Nulls.param(reason, "reason");
            this.reason = reason;
        }
    }
    public static final class Wrapper extends RuntimeException {
        public final String reason;
        public Wrapper(String reason) {
            super(Nulls.param(reason, "reason"));
            this.reason = reason;
        }
    }
    @SuppressWarnings("EffectivelyPrivate") private static final class Hidden {
        private final String name;
        public Hidden(String name) {
            this.name = name;
        }
        public String show(String text) {
            return name + text;
        }
    }
    public enum Kind {
        ADD("add"), DROP("drop");
        public final String label;
        Kind(String label) {
            this.label = label;
        }
    }
}
