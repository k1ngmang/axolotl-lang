package app;
import axl.lang.*;
import org.jspecify.annotations.*;

@NullMarked public final class Main {
    private Main() {}
    public interface Named {
        String name();
    }
    public static final class Opt implements Named {
        public final String name;
        private final @Nullable String description;
        private int uses;
        private final String shown;
        public Opt(String name, @Nullable String description, int uses) {
            Nulls.param(name, "name");
            this.name = name;
            this.description = description;
            this.uses = uses;
            this.shown = name + ": " + Nulls.or(description, () -> "no description");
        }
        public String describe() {
            uses += 1;
            return shown + " (" + uses + ")";
        }
        public boolean sameDescription(Opt other) {
            Nulls.param(other, "other");
            return java.util.Objects.equals(description, other.description);
        }
        @Override public String name() { return name; }
    }
    public static final class Secret implements Named {
        private final String name;
        public Secret(String name) {
            Nulls.param(name, "name");
            this.name = name;
        }
        public String masked() {
            return name.substring(0, 1) + "***";
        }
        @Override public String name() { return name; }
    }
    public static void main(String[] args) {
        final var o = new Opt("verbose", "print more", 0);
        System.out.println(o.describe());
        System.out.println(o.describe());
        System.out.println(o.sameDescription(new Opt("v", "print more", 0)));
        System.out.println(o.name);
        final var s = new Secret("hunter2");
        System.out.println(s.masked());
        final Named named = s;
        System.out.println(named.name().length());
    }
}
