package app;
import axl.lang.*;
import org.jspecify.annotations.*;
import static app.Checks.notBlank;

@NullMarked public final class Lib {
    private Lib() {}
    public static <T> T nonNull(@Nullable T value, String name) {
        Nulls.param(name, "name");
        return Nulls.or(value, () -> { throw new IllegalArgumentException(name + " must not be null"); });
    }
    public static String text(@Nullable String value, String name) {
        Nulls.param(name, "name");
        return Nulls.or(value, () -> { throw new IllegalArgumentException(name + " is missing"); });
    }
    public static int parse(String version) {
        return nonNull(version, "version").length();
    }
    public static String both(String first, String second) {
        nonNull(first, "first");
        text(second, "second");
        return first + second;
    }
    public static String local(String value) {
        final var checked = nonNull(value, "value");
        return checked.trim();
    }
    public static String sibling(String value) {
        return Checks.notEmpty(value, "value");
    }
    public static String imported(String value) {
        return notBlank(value);
    }
    public static String fromJava(String value) {
        return Validate.notNull(value, "value %s", "!");
    }
    public static String fromJavaText(String value) {
        return Validate.nonEmpty(value);
    }
    public static String printed(String value) {
        Nulls.param(value, "value");
        System.out.println(value);
        return nonNull(value, "value");
    }
    public static int logged(String value) {
        Nulls.param(value, "value");
        return log(value) + value.length();
    }
    public static int measured(String value) {
        Nulls.param(value, "value");
        return Validate.length(value);
    }
    public static int log(@Nullable String value) {
        return (value == null ? 0 : 1);
    }
    public static final class Range {
        private final String low;
        private final int high;
        public Range(String lowText, String highText) {
            this.low = nonNull(lowText, "low");
            this.high = text(highText, "high").length();
        }
        public String describe() {
            return low + high;
        }
    }
}
