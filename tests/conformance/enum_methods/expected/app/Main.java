package app;
import axl.lang.*;
import org.jspecify.annotations.*;
import java.util.function.Supplier;

@NullMarked public final class Main {
    private Main() {}
    public interface Named {
        String label();
        String describe(String prefix);
    }
    public sealed interface Shape {}
    public record Circle(double radius) implements Shape {}
    public enum Level implements Named, Shape {
        LOW("low", 1), HIGH("high", 10);
        public final String label;
        public final int weight;
        Level(String label, int weight) {
            this.label = label;
            this.weight = weight;
        }
        @Override public String describe(String prefix) {
            Nulls.param(prefix, "prefix");
            return prefix + label + "/" + weight + "/" + name();
        }
        public boolean heavier(Level other) {
            Nulls.param(other, "other");
            return weight > other.weight;
        }
        public Level next() {
            return (this == LOW ? HIGH : this);
        }
        @Override public String label() { return label; }
    }
    public enum Toggle implements Runnable {
        ON, OFF;
        public void run() {
            System.out.println("toggle " + name().toLowerCase());
        }
    }
    public static String read(Supplier<String> s) {
        Nulls.param(s, "s");
        return s.get();
    }
    public static String rank(Level level) {
        Nulls.param(level, "level");
        switch (level) {
            case Level.LOW -> {
                return "l";
            }
            case Level.HIGH -> {
                return "h";
            }
            default -> throw new IllegalStateException();
        }
    }
    public static String area(Shape shape) {
        Nulls.param(shape, "shape");
        switch (shape) {
            case Circle shape$1 -> {
                return "circle";
            }
            case Level shape$2 -> {
                return "level " + shape$2.weight;
            }
        }
    }
    public static void main(String[] args) {
        System.out.println(read(Predefined.TDF).length());
        System.out.println(Predefined.DEFAULT.get() + Predefined.DEFAULT.width());
        System.out.println(Predefined.named("TDF").width());
        final Named named = Level.HIGH;
        System.out.println(named.label() + " " + named.describe("> "));
        System.out.println(Level.HIGH.heavier(Level.LOW));
        System.out.println(Level.LOW.next() == Level.HIGH);
        System.out.println(rank(Level.LOW.next()));
        System.out.println(area(Level.LOW) + " " + area(new Circle(1.0)));
        Toggle.OFF.run();
        System.out.println(JavaCaller.formats());
    }
}
