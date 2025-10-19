package core;

import axl.lang.Err;
import axl.lang.Ok;
import axl.lang.Result;

/** Plain Java that calls Axolotl code. */
public final class Report {
    private Report() {}

    public static String describe(String raw) {
        return describe(Titles.title(raw));
    }

    public static String describe(Result<String, String> result) {
        return switch (result) {
            case Ok<String, String>(var slug) -> "title: " + slug;
            case Err<String, String>(var error) -> "error: " + error;
        };
    }
}
