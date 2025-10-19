package app;

/** Plain Java used from Axolotl code. */
public final class Units {
    private Units() {}

    public static double toCelsius(double fahrenheit) {
        return (fahrenheit - 32) * 5 / 9;
    }
}
