package app;
import axl.lang.*;
import org.jspecify.annotations.*;
import java.util.function.Supplier;

@NullMarked public enum Predefined implements Supplier<String> { DEFAULT(","), TDF("\t");
    public final String format;
    Predefined(String format) {
        this.format = format;
    }
    public String get() {
        return format;
    }
    public String getFormat() {
        return format;
    }
    public int width() {
        return format.length();
    }
    public static Predefined named(String name) {
        Nulls.param(name, "name");
        return Predefined.valueOf(name);
    }
}
