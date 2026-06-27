package app;
import axl.lang.*;
import org.jspecify.annotations.*;
import java.nio.file.Files;
import java.nio.file.Path;

@NullMarked public final class Main {
    private Main() {}
    public static long size(String path) {
        return Result.orThrow(() -> Files.size(Path.of(path)), e -> new IllegalStateException("no " + path, e));
    }
    public static long safeSize(String path) {
        final var caught = Result.attempt(() -> size(path), e -> "failed: " + e.getMessage());
        switch (caught) {
            case Ok<Long, String> caught$1 -> {
                return caught$1.value();
            }
            case Err<Long, String> caught$2 -> {
                return -1L;
            }
        }
    }
    public static void main(String[] args) {
        final int n = Result.orThrow(() -> Integer.parseInt("7"), e -> new RuntimeException(e));
        System.out.println(n + 1);
        final var parsed = Result.orThrow(() -> Integer.parseInt("8"), $ex1 -> new IllegalStateException("unparsable"));
        System.out.println(parsed * 2);
        System.out.println(safeSize("/nowhere"));
    }
}
