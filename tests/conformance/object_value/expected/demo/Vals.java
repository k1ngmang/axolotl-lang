package demo;
import axl.lang.*;
import org.jspecify.annotations.*;

@NullMarked public final class Vals {
    private Vals() {}
    public static final @Nullable Object prop = System.getProperties().get("axl.missing");
    public static void main(String[] args) {
        System.out.println(prop == null);
    }
}
