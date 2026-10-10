package app;
import axl.lang.*;
import org.jspecify.annotations.*;
import java.util.Map;
import java.util.Map.Entry;

@NullMarked public final class Entries {
    private Entries() {}
    public static String key(Entry<String, Integer> e) {
        Nulls.param(e, "e");
        return e.getKey();
    }
    public static void main(String[] args) {
        System.out.println(key(Map.entry("k", 1)));
    }
}
