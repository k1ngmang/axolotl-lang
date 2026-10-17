package app;

import java.util.function.Supplier;

/** Java calls the methods of the enum and passes a constant where its interface is expected. */
public final class JavaCaller {
    public static String formats() {
        Supplier<String> supplier = Predefined.TDF;
        return Predefined.DEFAULT.getFormat() + supplier.get().length() + Main.Level.LOW.describe("j:");
    }

    private JavaCaller() {
    }
}
