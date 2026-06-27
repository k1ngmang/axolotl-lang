package app;
import axl.lang.*;
import org.jspecify.annotations.*;
import java.util.List;

@NullMarked public final class Main {
    private Main() {}
    public record Todo(int id) {}
    public static void main(String[] args) {
        System.out.println(String.class.getSimpleName());
        System.out.println(Integer.class.getName());
        System.out.println(java.util.List.class.getName());
        System.out.println(List.class.getSimpleName());
        System.out.println(Todo.class.getSimpleName());
        final Class<String> c = String.class;
        System.out.println(c == String.class);
        final var boxes = List.of(Integer.class, Long.class, Double.class, Boolean.class);
        System.out.println(boxes);
    }
}
