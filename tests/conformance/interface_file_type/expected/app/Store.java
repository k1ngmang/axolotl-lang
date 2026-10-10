package app;
import axl.lang.*;
import org.jspecify.annotations.*;
import java.util.HashMap;
import java.util.Map;

@NullMarked public interface Store {
    @Nullable String get(String key);
    void put(String key, String value);
    public static final class MemoryStore implements Store {
        public final Map<String, String> values = new HashMap<String, String>();
        @Override public @Nullable String get(String key) {
            Nulls.param(key, "key");
            return values.get(key);
        }
        @Override public void put(String key, String value) {
            Nulls.param(key, "key");
            Nulls.param(value, "value");
            values.put(key, value);
        }
    }
    public static Store inMemory() {
        return new MemoryStore();
    }
    public static String describe(Store store, String key) {
        Nulls.param(store, "store");
        Nulls.param(key, "key");
        return key + "=" + Nulls.<String>or(store.get(key), () -> "unset");
    }
}
