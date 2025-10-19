package app;

import axl.lang.Err;
import axl.lang.Ok;
import axl.lang.Result;

/** Plain Java that calls Axolotl code. */
public final class Labels {
    private Labels() {}

    public static String describe(Result<Double, String> result) {
        return switch (result) {
            case Ok<Double, String>(var value) -> value + " C";
            case Err<Double, String>(var error) -> "error: " + error;
        };
    }

    public static String forText(String text) {
        return describe(Temperatures.celsius(text));
    }
}
