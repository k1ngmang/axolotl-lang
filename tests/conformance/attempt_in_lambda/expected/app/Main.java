package app;
import axl.lang.*;
import org.jspecify.annotations.*;
import java.util.List;

@NullMarked public final class Main {
    private Main() {}
    public static void main(String[] args) {
        final var xs = List.of("a", "b");
        xs.stream().forEach(it -> {
            try {
                Thread.sleep(1L);
            } catch (Exception e) {
                System.out.println("interrupted " + it);
            }
        });
        final var worker = new Thread(() -> {
            try {
                Thread.sleep(1L);
            } catch (Exception e) {
                System.out.println("interrupted");
            }
        });
        worker.start();
        try {
            worker.join();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        final Runnable task = () -> {
            try {
                Thread.sleep(1L);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        };
        task.run();
        final var values = xs.stream().map(Nulls.nonNull(it -> Result.attempt(() -> Integer.parseInt(it), e -> fallback(it)))).toList();
        System.out.println(values);
        System.out.println("done");
    }
    public static int fallback(String s) {
        return s.length();
    }
}
