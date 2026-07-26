package app;
import axl.lang.*;
import org.jspecify.annotations.*;
import java.io.BufferedReader;
import java.io.StringReader;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;

@NullMarked public final class Main {
    private Main() {}
    public static final class Tracker implements AutoCloseable {
        public final String name;
        public boolean closed;
        public Tracker(String name) {
            this.name = name;
            this.closed = false;
        }
        public void close() {
            closed = true;
            System.out.println("closed " + name);
        }
    }
    public static List<String> lines(String text) {
        final var out = new ArrayList<String>();
        try (var reader = new BufferedReader(new StringReader(text))) {
            var line = Result.orThrow(() -> reader.readLine(), e -> new IllegalStateException(e));
            while (line != null) {
                out.add(line);
                line = Result.orThrow(() -> reader.readLine(), e -> new IllegalStateException(e));
            }
        } catch (Exception e) {
            System.out.println("failed: " + e.getMessage());
        }
        return out;
    }
    public static long countWords(java.nio.file.Path path) {
        var words = 0L;
        try (var scanner = new Scanner(path, java.nio.charset.StandardCharsets.UTF_8)) {
            while (scanner.hasNext()) {
                scanner.next();
                words += 1;
            }
        } catch (Exception e) {
            System.out.println("cannot read " + path.getFileName() + ": " + e.getClass().getSimpleName());
        }
        return words;
    }
    public static void main(String[] args) {
        System.out.println(lines("a\nb\nc"));
        final var tracker = new Tracker("t");
        try (var t = tracker) {
            System.out.println("using " + t.name);
        }
        System.out.println(tracker.closed);
        final var file = Result.orThrow(() -> Files.createTempFile("axl", ".txt"), e -> new IllegalStateException(e));
        try {
            Files.writeString(file, "one two three");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        System.out.println(countWords(file));
        System.out.println(countWords(java.nio.file.Path.of("/nonexistent/none.txt")));
    }
}
