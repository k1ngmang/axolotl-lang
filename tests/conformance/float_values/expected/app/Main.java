package app;
import axl.lang.*;
import org.jspecify.annotations.*;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

@NullMarked public final class Main {
    private Main() {}
    public static String value(float value) {
        return "float " + value;
    }
    public static String value(double value) {
        return "double " + value;
    }
    public static String value(@Nullable String text) {
        return "string " + text;
    }
    public static float orZero(@Nullable Float value) {
        return Nulls.or(value, 0);
    }
    public static @Nullable Float pick(boolean first, @Nullable Float value) {
        return (first ? value : Float.valueOf(1));
    }
    public record Point(float x, float y) {}
    public static final class Decimal extends Number {
        public final String text;
        public Decimal(String text) {
            Nulls.param(text, "text");
            this.text = text;
        }
        public int intValue() {
            return Integer.parseInt(text);
        }
        public long longValue() {
            return Long.parseLong(text);
        }
        public float floatValue() {
            return Float.parseFloat(text);
        }
        public double doubleValue() {
            return Double.parseDouble(text);
        }
    }
    public static final class Parsed extends Base {
        private final String text;
        public Parsed(String text) {
            Nulls.param(text, "text");
            this.text = text;
        }
        public float asFloat() {
            return Float.parseFloat(text);
        }
    }
    public static final class Halver implements Function<Float, Float> {
        public Float apply(Float x) {
            Nulls.param(x, "x");
            return x / 2;
        }
    }
    public static String size(float f) {
        final var $e1 = f;
        switch ($e1 == 0f ? 0 : $e1 == 1.5f ? 1 : -1) {
            case 0 -> {
                return "none";
            }
            case 1 -> {
                return "one and a half";
            }
            default -> {
                return "more";
            }
        }
    }
    public static void main(String[] args) {
        System.out.println(Caller.overloads());
        System.out.println(Caller.subclasses());
        final var f = 1.5f;
        final float g = 2f;
        final var n = 3;
        final var wide = f + 0.25;
        final double d = f;
        final float l = 4L;
        System.out.println(f + n);
        System.out.println(wide);
        System.out.println(d + " " + l + " " + g);
        System.out.println(value(f) + ", " + value(1.5) + ", " + value(2f));
        System.out.println(f == 1.5f);
        System.out.println(f > n);
        System.out.println(orZero(null) + " " + orZero(2.5f));
        System.out.println(pick(true, null));
        System.out.println(pick(false, null));
        final var xs = new ArrayList<Float>();
        xs.add(1.25f);
        xs.add(f);
        System.out.println(((float) xs.get(0)) == ((float) xs.get(1)));
        final List<Float> ys = List.of(1f, 2.5f);
        System.out.println(ys);
        System.out.println(new Point(1f, 2.5f));
        System.out.println(new Decimal("7").floatValue() + new Decimal("2").intValue());
        System.out.println(new Halver().apply(5f));
        System.out.println(size(f) + " " + size(0f) + " " + size(7f));
        System.out.println(Double.valueOf(2.75).floatValue() + " " + Float.valueOf(f).intValue() + " " + Math.round(f));
        System.out.println(Float.MAX_VALUE);
    }
}
