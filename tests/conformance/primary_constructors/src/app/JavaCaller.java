package app;

import java.lang.reflect.Modifier;
import java.util.List;

/** Java sees the constructors as the header declares them: private, or public and deprecated. */
public final class JavaCaller {
    @SuppressWarnings("deprecation")
    public static String describe() {
        try {
            boolean parser = Modifier.isPrivate(Main.Parser.class.getDeclaredConstructor(boolean.class, Boolean.class).getModifiers());
            boolean builder = Main.Builder.class.getConstructor().isAnnotationPresent(Deprecated.class);
            var window = Main.Window.class.getDeclaredConstructor(List.class, int.class);
            String since = window.getAnnotation(Deprecated.class).since();
            boolean counter = Modifier.isPrivate(Main.Counter.class.getDeclaredConstructor().getModifiers());
            return parser + " " + builder + " " + Modifier.isPrivate(window.getModifiers()) + " " + since + " " + counter + " "
                    + new Main.Builder().add().count + " " + new Main.Parser(false).describe();
        } catch (NoSuchMethodException e) {
            return e.toString();
        }
    }

    private JavaCaller() {
    }
}
