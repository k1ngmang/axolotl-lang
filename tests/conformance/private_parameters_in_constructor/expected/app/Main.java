package app;
import axl.lang.*;
import org.jspecify.annotations.*;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

@NullMarked public final class Main {
    private Main() {}
    public interface Named {
        String name();
    }
    public static final class Opt {
        private final @Nullable String key;
        private final int argCount;
        private final boolean many;
        public Opt(@Nullable String option, boolean hasArg) {
            this.key = (option == null ? null : Objects.requireNonNull(option.trim()));
            this.argCount = (hasArg ? 1 : -1);
            this.many = argCount > 1;
        }
        public Opt(String option) {
            this(Nulls.param(option, "option"), false);
        }
        public String describe() {
            return key + "/" + argCount + "/" + many;
        }
    }
    public static final class Handler {
        private final Map<String, String> converters;
        public Handler(Map<String, String> converterMap) {
            this.converters = Objects.requireNonNull(converterMap, "converterMap");
        }
        public @Nullable String get(String k) {
            Nulls.param(k, "k");
            return converters.get(k);
        }
    }
    public static final class Failure extends RuntimeException {
        private final String status;
        private final String shown;
        private final String kind;
        public Failure(int code, @Nullable String reason, String fallback) {
            super(reason);
            Nulls.param(fallback, "fallback");
            this.status = "E" + code;
            this.shown = Nulls.<String>or(reason, () -> fallback);
            this.kind = (code == 404 ? "missing" : "other");
        }
        public String describe() {
            return status + " " + shown + " " + kind;
        }
    }
    public static final class Kept implements Named {
        private final String label;
        private final String prefix;
        private int uses;
        private final String name;
        private final String first;
        private final Supplier<String> shout;
        public Kept(String label, String prefix, int uses, String name) {
            Nulls.param(label, "label");
            Nulls.param(prefix, "prefix");
            Nulls.param(name, "name");
            this.label = label;
            this.prefix = prefix;
            this.uses = uses;
            this.name = name;
            this.first = label + uses;
            this.shout = () -> Kept.this.prefix.toUpperCase();
        }
        public String describe() {
            uses += 1;
            return first + " " + label.length() + " " + shout.get() + " " + uses;
        }
        public boolean same(Kept other) {
            Nulls.param(other, "other");
            return first.equals(other.first);
        }
        @Override public String name() { return name; }
    }
    public static void main(String[] args) {
        System.out.println(new Opt("  v ", true).describe());
        System.out.println(new Opt("x").describe());
        System.out.println(new Handler(Map.of("a", "b")).get("a"));
        System.out.println(new Failure(404, null, "not found").describe());
        System.out.println(new Failure(500, "broken", "-").getMessage());
        final var kept = new Kept("l", "p", 0, "n");
        System.out.println(kept.describe());
        System.out.println(kept.same(new Kept("l", "q", 0, "m")));
        final Named named = kept;
        System.out.println(named.name());
    }
}
