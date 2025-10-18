package lib;

import axl.lang.Err;
import axl.lang.Ok;
import axl.lang.Result;

public final class Lookup {
    private Lookup() {}

    public static Result<String, String> find(String key) {
        return key.isEmpty() ? new Err<>("empty key") : new Ok<>(key.toUpperCase());
    }
}
