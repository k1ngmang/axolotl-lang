package app;
import axl.lang.*;
import org.jspecify.annotations.*;
import java.util.function.Supplier;

@NullMarked public enum Level implements Supplier<Level.Note> { LOW, HIGH;
    public Note get() {
        return new Note(name());
    }
    public record Note(String text) {
        public Note {
            Nulls.param(text, "text");
        }
    }
}
