package app;
import axl.lang.*;
import org.jspecify.annotations.*;

@NullMarked public final class Main {
    private Main() {}
    public enum Planet {
        MERCURY(3.303e23, 2.4397e6), EARTH(5.976e24, 6.37814e6);
        public final double mass;
        public final double radius;
        Planet(double mass, double radius) {
            this.mass = mass;
            this.radius = radius;
        }
    }
    public enum Level {
        LOW(1, "low"), HIGH(3, null);
        public final int code;
        public final @Nullable String label;
        Level(int code, @Nullable String label) {
            this.code = code;
            this.label = label;
        }
    }
    public static double gravity(Planet p) {
        return 6.67300E-11 * p.mass / (p.radius * p.radius);
    }
    public static String describe(Level level) {
        switch (level) {
            case Level.LOW -> {
                return level.code + " " + Nulls.or(level.label, () -> "-");
            }
            case Level.HIGH -> {
                return level.code + " " + Nulls.or(level.label, () -> "-");
            }
            default -> throw new IllegalStateException();
        }
    }
    public static void main(String[] args) {
        System.out.println(Math.round(gravity(Planet.EARTH) * 100) / 100.0);
        System.out.println(Planet.MERCURY.mass > Planet.EARTH.mass);
        System.out.println(describe(Level.LOW));
        System.out.println(describe(Level.HIGH));
        System.out.println(Level.valueOf("HIGH").code);
    }
}
