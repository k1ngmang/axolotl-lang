package app;
import axl.lang.*;
import org.jspecify.annotations.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.OpenOption;
import java.nio.file.StandardOpenOption;
import java.util.List;

@NullMarked public final class Main {
    private Main() {}
    public static void main(String[] args) {
        final var file = Result.orThrow(() -> Files.createTempFile("varargs", ".txt"), e -> new IllegalStateException(e));
        final List<OpenOption> options = List.of(StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        try (var out = Files.newBufferedWriter(file, StandardCharsets.UTF_8, options.toArray(new OpenOption[0]))) {
            out.write("one");
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
        final var more = List.of(StandardOpenOption.APPEND);
        try {
            Files.writeString(file, " two", more.toArray(new OpenOption[0]));
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
        System.out.println(Result.orThrow(() -> Files.readString(file), e -> new IllegalStateException(e)));
        try {
            Files.delete(file);
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
