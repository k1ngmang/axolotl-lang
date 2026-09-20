package app;

import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

@NullMarked
public class Labels {
    public static @Nullable String label(String key) {
        return key.isEmpty() ? null : key;
    }
}
