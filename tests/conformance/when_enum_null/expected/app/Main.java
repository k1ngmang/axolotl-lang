package app;
import axl.lang.*;
import org.jspecify.annotations.*;

@NullMarked public final class Main {
    private Main() {}
    public enum Dir { N, E }
    public static String name(@Nullable Dir d) {
        switch (d) {
            case Dir.N -> {
                return "n";
            }
            case Dir.E -> {
                return "e";
            }
            case null -> {
                return "none";
            }
            default -> throw new IllegalStateException();
        }
    }
    public static void main(String[] args) {
        System.out.println(name(null) + name(Dir.N) + name(Dir.E));
    }
}
