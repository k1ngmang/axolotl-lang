package app;
import axl.lang.*;
import org.jspecify.annotations.*;

@NullMarked public final class Main {
    private Main() {}
    public static void main(String[] args) {
        final var store = Store.inMemory();
        System.out.println(Store.describe(store, "color"));
        store.put("color", "red");
        System.out.println(Store.describe(store, "color"));
    }
}
