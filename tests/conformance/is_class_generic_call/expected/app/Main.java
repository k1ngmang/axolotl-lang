package app;
import axl.lang.*;
import org.jspecify.annotations.*;
import java.util.List;

@NullMarked public final class Main {
    private Main() {}
    public static <T> T create(String s, Class<T> clazz) {
        Nulls.param(s, "s");
        Nulls.param(clazz, "clazz");
        return clazz.cast(s);
    }
    public static Object create(String s, Object obj) {
        Nulls.param(s, "s");
        Nulls.param(obj, "obj");
        if (!(obj instanceof Class<?> obj$1)) {
            throw new ClassCastException("not a class");
        }
        return create(s, obj$1);
    }
    public static <T extends @Nullable Object> T firstOf(List<T> xs) {
        Nulls.param(xs, "xs");
        return xs.get(0);
    }
    public static @Nullable Object first(Object o) {
        Nulls.param(o, "o");
        switch (o) {
            case List<?> o$1 -> {
                return firstOf(o$1);
            }
            default -> {
                return null;
            }
        }
    }
    public static void main(String[] args) {
        System.out.println(create("x", String.class));
        System.out.println(first(List.of("y", "z")));
    }
}
