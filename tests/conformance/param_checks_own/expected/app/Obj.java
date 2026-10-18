package app;
import axl.lang.*;
import org.jspecify.annotations.*;
import java.util.Objects;

@NullMarked public final class Obj {
    public Obj add(String name, String value, String label) {
        Nulls.param(label, "label");
        Objects.requireNonNull(name, "name is null");
        Objects.requireNonNull(value, () -> "value is null");
        return this;
    }
    public Obj set(String name, int index) {
        Nulls.param(name, "name");
        System.out.println(index);
        Objects.requireNonNull(name, "name is null");
        return this;
    }
    public static String wrapped(String text) {
        return Objects.requireNonNull(text, "text is null");
    }
    public static final class Str {
        public final String note;
        private final String string;
        public Str(String text, String note) {
            Nulls.param(note, "note");
            this.note = note;
            this.string = Objects.requireNonNull(text, "string is null");
        }
        public String get() {
            return string + note;
        }
    }
}
