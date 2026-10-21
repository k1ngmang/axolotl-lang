package app;
import axl.lang.*;
import org.jspecify.annotations.*;
import java.io.IOException;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

@NullMarked public final class Main {
    private Main() {}
    public static String read(Path p) {
        Nulls.param(p, "p");
        try {
            Result.mayThrow(IOException.class);
            return Files.readString(p);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
    public static Result<Long, String> size(Path p) {
        Nulls.param(p, "p");
        return Result.attempt(() -> Files.size(p), e -> "cannot read " + e.getMessage(), IOException.class);
    }
    public static Result<Integer, String> number(String s) {
        Nulls.param(s, "s");
        return Result.attempt(() -> Integer.parseInt(s), e -> "not a number: " + s, NumberFormatException.class);
    }
    public static Result<Integer, String> parsedOrState(String s) {
        Nulls.param(s, "s");
        return Result.attempt(() -> Integer.parseInt(s), e -> "illegal state", IllegalStateException.class);
    }
    public static void put(Appendable out, String text) {
        Nulls.param(out, "out");
        Nulls.param(text, "text");
        try {
            Result.mayThrow(IOException.class);
            out.append(text);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
    public static int firstChar(String text) {
        Nulls.param(text, "text");
        try (var reader = new StringReader(text)) {
            Result.mayThrow(IOException.class);
            return reader.read();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
    public static int deleted(List<Path> paths) {
        Nulls.param(paths, "paths");
        var n = 0;
        for (final var p : paths) {
            try {
                Result.mayThrow(IOException.class);
                Files.delete(p);
            } catch (IOException e) {
                continue;
            }
            n += 1;
        }
        return n;
    }
    public static String pause() {
        try {
            Result.mayThrow(InterruptedException.class);
            Thread.sleep(1L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "interrupted";
        }
        return "rested";
    }
    public static void main(String[] args) {
        System.out.println(number("12"));
        System.out.println(number("x"));
        System.out.println(size(Path.of("no-such-file")));
        final var sb = new StringBuilder();
        put(sb, "abc");
        System.out.println(sb);
        System.out.println(firstChar("z"));
        System.out.println(deleted(List.of(Path.of("/no/such/a"), Path.of("/no/such/b"))));
        final var unchecked = Result.attempt(() -> read(Path.of("no-such-file")), e -> e.getClass().getSimpleName(), UncheckedIOException.class);
        System.out.println(unchecked);
        System.out.println(Result.attempt(() -> parsedOrState("q"), e -> "passed on: " + e.getMessage(), NumberFormatException.class));
        System.out.println(pause());
    }
}
