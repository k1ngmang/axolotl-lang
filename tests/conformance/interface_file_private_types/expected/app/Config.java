package app;
import axl.lang.*;
import org.jspecify.annotations.*;
import java.io.Writer;
import app.Config.Private$.Minimal;
import app.Config.Private$.Pair;
import app.Config.Private$.Mode;
import app.Config.Private$.Step;

@NullMarked public interface Config {
    String create(Writer writer);
    public static final Config MINIMAL = new Minimal(Mode.PLAIN);
    private static String prefix() {
        return "minimal";
    }
    public static String describe(Config config) {
        Nulls.param(config, "config");
        switch (config) {
            case Minimal config$1 -> {
                return "the minimal one: " + config$1.create(Writer.nullWriter());
            }
            default -> {
                return "another";
            }
        }
    }
    final class Private$ {
        private Private$() {}
        static final class Minimal implements Config, Step {
            public final Mode mode;
            public Minimal(Mode mode) {
                this.mode = mode;
            }
            @Override public String create(Writer writer) {
                return prefix() + " " + mode + " " + new Pair(1, 2).sum() + " " + apply(3);
            }
            @Override public int apply(int n) {
                return n * 2;
            }
        }
        record Pair(int a, int b) {
            public int sum() {
                return a + b;
            }
        }
        enum Mode { PLAIN, PRETTY }
        interface Step {
            int apply(int n);
        }
    }
}
