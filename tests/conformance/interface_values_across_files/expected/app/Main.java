package app;
import axl.lang.*;
import org.jspecify.annotations.*;

@NullMarked public final class Main {
    private Main() {}
    public static final class Polite implements Greeter {
        public final String name;
        public Polite(String name) {
            Nulls.param(name, "name");
            this.name = name;
        }
        @Override public String greet(String who) {
            Nulls.param(who, "who");
            return "Dear " + who + ", " + name;
        }
        @Override public String name() { return name; }
    }
    public static final class Terse implements Greeter {
        public final String name = "terse";
        @Override public String greet(String who) {
            Nulls.param(who, "who");
            return who;
        }
        @Override public String name() { return name; }
    }
    public static void main(String[] args) {
        final java.util.List<Greeter> greeters = java.util.List.of(new Polite("ann"), new Terse());
        for (final var g : greeters) {
            System.out.println(g.name() + ": " + g.greet("bob"));
        }
    }
}
