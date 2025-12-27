package app;
import axl.lang.*;
import org.jspecify.annotations.*;
import java.util.List;

@NullMarked public final class Main {
    private Main() {}
    public static void main(String[] args) {
        final Runnable t = () -> List.of(1, 2).stream().forEach(it -> System.out.println(it));
        t.run();
        final Runnable u = () -> List.of(3, 4).forEach(it -> System.out.println(it));
        u.run();
    }
}
