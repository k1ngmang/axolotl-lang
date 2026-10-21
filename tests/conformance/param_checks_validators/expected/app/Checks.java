package app;
import axl.lang.*;
import org.jspecify.annotations.*;

@NullMarked public final class Checks {
    private Checks() {}
    public static String notEmpty(@Nullable String value, String name) {
        Nulls.param(name, "name");
        if (value == null || value.isEmpty()) {
            throw new IllegalArgumentException(name + " is empty");
        }
        return value;
    }
    public static String notBlank(@Nullable String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("blank");
        }
        return value;
    }
}
