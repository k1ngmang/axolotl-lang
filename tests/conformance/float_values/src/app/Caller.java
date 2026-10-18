package app;

public class Caller {
    public static String overloads() {
        return Main.value(3.14f) + ", " + Main.value(3.14) + ", " + Main.value((String) null) + ", " + Main.orZero(null);
    }

    public static String subclasses() {
        Number number = new Main.Decimal("3");
        Base base = new Main.Parsed("0.5");
        return number.floatValue() + " " + base.asFloat();
    }
}
