package app;
import axl.lang.*;
import org.jspecify.annotations.*;
import java.util.List;
import java.util.Map;

@NullMarked public final class Main {
    private Main() {}
    public static int size(@Nullable Object v) {
        switch (v) {
            case List v$1 -> {
                return v$1.size();
            }
            case Map v$2 -> {
                return v$2.size();
            }
            case null, default -> {
                return 0;
            }
        }
    }
    public static void main(String[] args) {
        final Object o = List.of(1, 2);
        if (o instanceof List o$1) {
            System.out.println(o$1.size());
        }
        System.out.println(!(o instanceof Map o$2));
        System.out.println(size(Map.of("a", 1)));
    }
}
